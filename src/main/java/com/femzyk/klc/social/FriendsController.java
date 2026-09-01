package com.femzyk.klc.social;

import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.db.DatabaseManager;
import com.femzyk.klc.util.EmailService;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.sql.*;
import java.util.UUID;

/**
 * FriendsController - KLC CBT Suite v1.0 HOTFIX (A5)
 *
 * FIX: the LIVE cloud friendships table uses receiver_id (NOT NULL),
 * not addressee_id. Previous version inserted addressee_id ->
 * "null value in column receiver_id violates not-null constraint".
 * All queries now use receiver_id. H2 self-create matches.
 * All features preserved: search, request, accept/decline, unfriend,
 * email notify, guards, audit.
 */
public class FriendsController {

    @FXML private TextField searchField;
    @FXML private TableView<UserRow> searchTable;
    @FXML private TableColumn<UserRow, String> colSrchName, colSrchRole, colSrchAdm;

    @FXML private ListView<ReqRow>  requestsList;
    @FXML private ListView<FriendRow> friendsList;
    @FXML private Label statusLabel;

    private final ObservableList<UserRow>   searchData  = FXCollections.observableArrayList();
    private final ObservableList<ReqRow>    requestData = FXCollections.observableArrayList();
    private final ObservableList<FriendRow> friendData  = FXCollections.observableArrayList();

    public static class UserRow {
        String id, name, role, adm;
        UserRow(String i, String n, String r, String a) { id = i; name = n; role = r; adm = a; }
        public String getName() { return name; }
        public String getRole() { return role; }
        public String getAdm()  { return adm == null ? "-" : adm; }
    }
    public static class ReqRow {
        String friendshipId, requesterId, name;
        ReqRow(String f, String r, String n) { friendshipId = f; requesterId = r; name = n; }
        @Override public String toString() { return name + "  (wants to be your friend)"; }
    }
    public static class FriendRow {
        String friendshipId, friendUserId, name, role;
        FriendRow(String f, String u, String n, String r) {
            friendshipId = f; friendUserId = u; name = n; role = r; }
        @Override public String toString() { return name + "  [" + role + "]"; }
    }

    @FXML
    public void initialize() {
        ensureTable();

        colSrchName.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(c.getValue().getName()));
        colSrchRole.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(c.getValue().getRole()));
        colSrchAdm.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(c.getValue().getAdm()));
        searchTable.setItems(searchData);
        requestsList.setItems(requestData);
        friendsList.setItems(friendData);

        loadRequests();
        loadFriends();
    }

    private void ensureTable() {
        try (Connection c = DatabaseManager.getConnection()) {
            boolean h2 = c.getMetaData().getDatabaseProductName()
                          .toLowerCase().contains("h2");
            if (h2) {
                try (Statement s = c.createStatement()) {
                    s.execute(
                        "CREATE TABLE IF NOT EXISTS friendships (" +
                        "  id           VARCHAR(36) DEFAULT RANDOM_UUID() PRIMARY KEY," +
                        "  requester_id VARCHAR(36)," +
                        "  receiver_id  VARCHAR(36)," +
                        "  status       VARCHAR(20) DEFAULT 'PENDING'," +
                        "  created_at   TIMESTAMP   DEFAULT CURRENT_TIMESTAMP)");
                    // Upgrade older offline caches that used addressee_id
                    try { s.execute("ALTER TABLE friendships ADD COLUMN IF NOT EXISTS receiver_id VARCHAR(36)"); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
    }

    @FXML
    private void searchUsers() {
        String q = searchField == null ? "" : searchField.getText().trim();
        if (q.isBlank()) {
            setStatus("Type a name or admission number to search.", true);
            return;
        }
        searchData.clear();
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT u.id, u.full_name, u.role, sp.admission_no " +
                 "FROM users u " +
                 "LEFT JOIN student_profiles sp ON sp.user_id = u.id " +
                 "WHERE u.is_active = TRUE AND u.id <> ? " +
                 "  AND (LOWER(u.full_name) LIKE ? " +
                 "       OR LOWER(COALESCE(sp.admission_no,'')) LIKE ?) " +
                 "ORDER BY u.full_name LIMIT 25")) {
            AuthService.setUuid(ps, 1, AuthService.Session.userId, c);
            String like = "%" + q.toLowerCase() + "%";
            ps.setString(2, like);
            ps.setString(3, like);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                searchData.add(new UserRow(
                    rs.getString(1), rs.getString(2),
                    rs.getString(3), rs.getString(4)));
            }
            setStatus(searchData.isEmpty()
                ? "No users found for '" + q + "'."
                : searchData.size() + " user(s) found. Select one and " +
                  "click Send Friend Request.", false);
        } catch (Exception e) {
            setStatus("Search error: " + e.getMessage(), true);
        }
    }

    @FXML
    private void sendRequest() {
        UserRow sel = searchTable.getSelectionModel().getSelectedItem();
        if (sel == null) {
            setStatus("Select a user from the search results first.", true);
            return;
        }

        // KLC v1.0 safeguarding: students may not send friend requests to
        // staff accounts by default (config:
        // social.allow_student_staff_dm=true to lift for the school).
        if ("STUDENT".equals(AuthService.Session.role)
                && !"STUDENT".equals(sel.role)
                && !com.femzyk.klc.util.ConfigService.flag(
                    "social.allow_student_staff_dm", false)) {
            setStatus("Students cannot add staff as friends. Please contact "
                + "your teacher through the school office.", true);
            return;
        }

        try (Connection c = DatabaseManager.getConnection()) {
            try (PreparedStatement chk = c.prepareStatement(
                    "SELECT status FROM friendships " +
                    "WHERE (requester_id = ? AND receiver_id = ?) " +
                    "   OR (requester_id = ? AND receiver_id = ?)")) {
                AuthService.setUuid(chk, 1, AuthService.Session.userId, c);
                AuthService.setUuid(chk, 2, sel.id, c);
                AuthService.setUuid(chk, 3, sel.id, c);
                AuthService.setUuid(chk, 4, AuthService.Session.userId, c);
                ResultSet rs = chk.executeQuery();
                if (rs.next()) {
                    String st = rs.getString(1);
                    setStatus("ACCEPTED".equals(st)
                        ? "You are already friends with " + sel.name + "."
                        : "A request between you and " + sel.name +
                          " already exists (status: " + st + ").", true);
                    return;
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO friendships(id, requester_id, receiver_id, status) " +
                    "VALUES(?,?,?, 'PENDING')")) {
                AuthService.setUuid(ps, 1, UUID.randomUUID().toString(), c);
                AuthService.setUuid(ps, 2, AuthService.Session.userId, c);
                AuthService.setUuid(ps, 3, sel.id, c);
                ps.executeUpdate();
            }
            try (PreparedStatement em = c.prepareStatement(
                    "SELECT email FROM users WHERE id = ?")) {
                AuthService.setUuid(em, 1, sel.id, c);
                ResultSet rs = em.executeQuery();
                if (rs.next() && rs.getString(1) != null) {
                    try {
                        EmailService.sendFriendRequest(
                            rs.getString(1), sel.name,
                            AuthService.Session.fullName);
                    } catch (Throwable ignored) {}
                }
            }
            AuthService.logAudit("FRIEND_REQUEST", "friendships", sel.id);
            setStatus("Friend request sent to " + sel.name + ".", false);
        } catch (Exception e) {
            setStatus("Error: " + e.getMessage(), true);
        }
    }

    @FXML
    public void loadRequests() {
        requestData.clear();
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT f.id, f.requester_id, u.full_name " +
                 "FROM friendships f " +
                 "JOIN users u ON u.id = f.requester_id " +
                 "WHERE f.receiver_id = ? AND f.status = 'PENDING' " +
                 "ORDER BY f.created_at DESC")) {
            AuthService.setUuid(ps, 1, AuthService.Session.userId, c);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                requestData.add(new ReqRow(
                    rs.getString(1), rs.getString(2), rs.getString(3)));
            }
        } catch (Exception e) {
            setStatus("Error loading requests: " + e.getMessage(), true);
        }
    }

    @FXML
    private void acceptRequest() {
        ReqRow sel = requestsList.getSelectionModel().getSelectedItem();
        if (sel == null) { setStatus("Select a request to accept.", true); return; }
        updateRequestStatus(sel, "ACCEPTED",
            "You are now friends with " + sel.name + ". You can message each other.");
    }

    @FXML
    private void declineRequest() {
        ReqRow sel = requestsList.getSelectionModel().getSelectedItem();
        if (sel == null) { setStatus("Select a request to decline.", true); return; }
        updateRequestStatus(sel, "DECLINED",
            "Request from " + sel.name + " declined.");
    }

    private void updateRequestStatus(ReqRow sel, String status, String msg) {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE friendships SET status = ? WHERE id = ?")) {
            ps.setString(1, status);
            AuthService.setUuid(ps, 2, sel.friendshipId, c);
            ps.executeUpdate();
            AuthService.logAudit("FRIEND_" + status, "friendships", sel.friendshipId);
            setStatus(msg, false);
            loadRequests();
            loadFriends();
        } catch (Exception e) {
            setStatus("Error: " + e.getMessage(), true);
        }
    }

    @FXML
    public void loadFriends() {
        friendData.clear();
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT f.id, u.id, u.full_name, u.role " +
                 "FROM friendships f " +
                 "JOIN users u ON (u.id = f.requester_id OR u.id = f.receiver_id) " +
                 "WHERE f.status = 'ACCEPTED' " +
                 "  AND (f.requester_id = ? OR f.receiver_id = ?) " +
                 "  AND u.id <> ? " +
                 "ORDER BY u.full_name")) {
            AuthService.setUuid(ps, 1, AuthService.Session.userId, c);
            AuthService.setUuid(ps, 2, AuthService.Session.userId, c);
            AuthService.setUuid(ps, 3, AuthService.Session.userId, c);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                friendData.add(new FriendRow(
                    rs.getString(1), rs.getString(2),
                    rs.getString(3), rs.getString(4)));
            }
        } catch (Exception e) {
            setStatus("Error loading friends: " + e.getMessage(), true);
        }
    }

    @FXML
    private void unfriend() {
        FriendRow sel = friendsList.getSelectionModel().getSelectedItem();
        if (sel == null) { setStatus("Select a friend to remove.", true); return; }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "Remove " + sel.name + " from your friends?",
            ButtonType.YES, ButtonType.NO);
        confirm.showAndWait().ifPresent(btn -> {
            if (btn != ButtonType.YES) return;
            try (Connection c = DatabaseManager.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM friendships WHERE id = ?")) {
                AuthService.setUuid(ps, 1, sel.friendshipId, c);
                ps.executeUpdate();
                setStatus(sel.name + " removed from friends.", false);
                loadFriends();
            } catch (Exception e) {
                setStatus("Error: " + e.getMessage(), true);
            }
        });
    }

    @FXML
    private void refreshAll() {
        loadRequests();
        loadFriends();
        setStatus("Refreshed.", false);
    }

    private void setStatus(String m, boolean err) {
        if (statusLabel == null) return;
        statusLabel.setText(m);
        statusLabel.setStyle(err
            ? "-fx-text-fill:#ef4444; -fx-font-weight:bold;"
            : "-fx-text-fill:#0f7a3a; -fx-font-weight:bold;");
    }
}

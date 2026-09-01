package com.femzyk.klc.social;

import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.db.DatabaseManager;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.awt.Desktop;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.*;
import java.util.UUID;

/**
 * MessagesController - KLC CBT Suite v1.0 (G7 - NEW FEATURE)
 *
 * In-app messaging between ACCEPTED friends only:
 * - Friends list on the left; select a friend to open the chat
 * - Message history, sent messages right-tagged, unread auto-marked
 *   read on open
 * - ATTACHMENTS (per requirement): PDF, DOCX, and images can be
 *   attached. Files are copied to klc_assets/attachments/ and sent as
 *   an [ATTACHMENT] message. Double-click an attachment message to
 *   open the file with the system viewer.
 * - 15s auto-refresh of the open chat (secure polling, Rule 3 - no
 *   sockets); self-stops when the screen is left.
 *
 * Rule 11 notes: setUuid everywhere; H2 table self-created;
 * friendship verified server-side before EVERY send (never trust UI).
 */
public class MessagesController {

    private static final int REFRESH_SECONDS = 15;
    private static final String ATTACH_PREFIX = "[ATTACHMENT]";

    @FXML private ListView<FriendRow> chatFriendsList;
    @FXML private Label   chatWithLabel;
    @FXML private ListView<MsgRow> messageList;
    @FXML private TextField messageField;
    @FXML private Label statusLabel;

    private final ObservableList<FriendRow> friendData = FXCollections.observableArrayList();
    private final ObservableList<MsgRow>    msgData    = FXCollections.observableArrayList();

    private FriendRow currentFriend = null;
    private Timeline autoRefresh;

    public static class FriendRow {
        String userId, name, role;
        int unread;
        FriendRow(String u, String n, String r, int un) {
            userId = u; name = n; role = r; unread = un; }
        @Override public String toString() {
            return name + (unread > 0 ? "   (" + unread + " new)" : "");
        }
    }

    public static class MsgRow {
        String content, time;
        boolean mine;
        String attachmentPath; // non-null when this is an attachment
        MsgRow(String c, String t, boolean m, String att) {
            content = c; time = t; mine = m; attachmentPath = att; }
        @Override public String toString() {
            String label = attachmentPath != null
                ? "FILE: " + new File(attachmentPath).getName() +
                  "  (double-click to open)"
                : content;
            return mine ? "You  [" + time + "]:  " + label
                        : "[" + time + "]  " + label;
        }
    }

    @FXML
    public void initialize() {
        ensureTable();

        chatFriendsList.setItems(friendData);
        messageList.setItems(msgData);

        chatFriendsList.getSelectionModel().selectedItemProperty()
            .addListener((o, ov, nv) -> {
                if (nv != null) openChat(nv);
            });

        // Double-click an attachment message to open the file
        messageList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                MsgRow sel = messageList.getSelectionModel().getSelectedItem();
                if (sel != null && sel.attachmentPath != null)
                    openAttachment(sel.attachmentPath);
            }
        });

        if (messageField != null)
            messageField.setOnAction(e -> sendMessage());

        loadFriends();
        startAutoRefresh();
    }

    private void ensureTable() {
        try (Connection c = DatabaseManager.getConnection()) {
            boolean h2 = c.getMetaData().getDatabaseProductName()
                          .toLowerCase().contains("h2");
            if (h2) {
                try (Statement s = c.createStatement()) {
                    s.execute(
                        "CREATE TABLE IF NOT EXISTS messages (" +
                        "  id          VARCHAR(36) DEFAULT RANDOM_UUID() PRIMARY KEY," +
                        "  sender_id   VARCHAR(36)," +
                        "  receiver_id VARCHAR(36)," +
                        "  content     TEXT," +
                        "  is_read     BOOLEAN     DEFAULT FALSE," +
                        "  created_at  TIMESTAMP   DEFAULT CURRENT_TIMESTAMP)");
                    // KLC v1.0 FIX: use receiver_id (matches live cloud schema
                    // and FriendsController). The old addressee_id DDL created
                    // an incompatible offline friendships table.
                    s.execute(
                        "CREATE TABLE IF NOT EXISTS friendships (" +
                        "  id           VARCHAR(36) DEFAULT RANDOM_UUID() PRIMARY KEY," +
                        "  requester_id VARCHAR(36)," +
                        "  receiver_id  VARCHAR(36)," +
                        "  status       VARCHAR(20) DEFAULT 'PENDING'," +
                        "  created_at   TIMESTAMP   DEFAULT CURRENT_TIMESTAMP)");
                    // Upgrade older offline caches created with addressee_id
                    try { s.execute("ALTER TABLE friendships ADD COLUMN IF NOT EXISTS receiver_id VARCHAR(36)"); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
    }

    private void startAutoRefresh() {
        stopAutoRefresh();
        autoRefresh = new Timeline(new KeyFrame(
            Duration.seconds(REFRESH_SECONDS), e -> {
                if (chatFriendsList == null
                        || chatFriendsList.getScene() == null) {
                    stopAutoRefresh(); return;
                }
                loadFriends();
                if (currentFriend != null) loadMessages(false);
            }));
        autoRefresh.setCycleCount(Timeline.INDEFINITE);
        autoRefresh.play();
    }

    private void stopAutoRefresh() {
        if (autoRefresh != null) { autoRefresh.stop(); autoRefresh = null; }
    }

    // ── FRIENDS (accepted only) with unread counts ───────────────────────
    @FXML
    public void loadFriends() {
        String keepId = currentFriend == null ? null : currentFriend.userId;
        friendData.clear();
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT u.id, u.full_name, u.role, " +
                 "  (SELECT COUNT(*) FROM messages m " +
                 "   WHERE m.sender_id = u.id AND m.receiver_id = ? " +
                 "     AND m.is_read = FALSE) AS unread " +
                 "FROM friendships f " +
                 "JOIN users u ON (u.id = f.requester_id OR u.id = f.receiver_id) " +
                 "WHERE f.status = 'ACCEPTED' " +
                 "  AND (f.requester_id = ? OR f.receiver_id = ?) " +
                 "  AND u.id <> ? " +
                 "ORDER BY u.full_name")) {
            AuthService.setUuid(ps, 1, AuthService.Session.userId, c);
            AuthService.setUuid(ps, 2, AuthService.Session.userId, c);
            AuthService.setUuid(ps, 3, AuthService.Session.userId, c);
            AuthService.setUuid(ps, 4, AuthService.Session.userId, c);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                FriendRow fr = new FriendRow(
                    rs.getString(1), rs.getString(2),
                    rs.getString(3), rs.getInt(4));
                friendData.add(fr);
                if (keepId != null && keepId.equals(fr.userId))
                    currentFriend = fr;
            }
        } catch (Exception e) {
            setStatus("Error loading friends: " + e.getMessage(), true);
        }
    }

    private void openChat(FriendRow friend) {
        currentFriend = friend;
        if (chatWithLabel != null)
            chatWithLabel.setText("Chat with " + friend.name);
        loadMessages(true);
    }

    /** Verify ACCEPTED friendship server-side (never trust the UI). */
    private boolean isFriend(Connection c, String otherId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM friendships " +
                "WHERE status = 'ACCEPTED' " +
                "  AND ((requester_id = ? AND receiver_id = ?) " +
                "    OR (requester_id = ? AND receiver_id = ?))")) {
            AuthService.setUuid(ps, 1, AuthService.Session.userId, c);
            AuthService.setUuid(ps, 2, otherId, c);
            AuthService.setUuid(ps, 3, otherId, c);
            AuthService.setUuid(ps, 4, AuthService.Session.userId, c);
            ResultSet rs = ps.executeQuery();
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    private void loadMessages(boolean markRead) {
        if (currentFriend == null) return;
        msgData.clear();
        try (Connection c = DatabaseManager.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT sender_id, content, created_at FROM messages " +
                    "WHERE (sender_id = ? AND receiver_id = ?) " +
                    "   OR (sender_id = ? AND receiver_id = ?) " +
                    "ORDER BY created_at ASC")) {
                AuthService.setUuid(ps, 1, AuthService.Session.userId, c);
                AuthService.setUuid(ps, 2, currentFriend.userId, c);
                AuthService.setUuid(ps, 3, currentFriend.userId, c);
                AuthService.setUuid(ps, 4, AuthService.Session.userId, c);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    boolean mine = rs.getString(1)
                        .equals(AuthService.Session.userId);
                    String content = rs.getString(2) == null
                        ? "" : rs.getString(2);
                    Timestamp ts = rs.getTimestamp(3);
                    String time = ts == null ? "-"
                        : ts.toLocalDateTime().toLocalDate() + " " +
                          String.format("%02d:%02d",
                              ts.toLocalDateTime().getHour(),
                              ts.toLocalDateTime().getMinute());
                    String attach = null;
                    if (content.startsWith(ATTACH_PREFIX)) {
                        attach = content.substring(ATTACH_PREFIX.length()).trim();
                    }
                    msgData.add(new MsgRow(content, time, mine, attach));
                }
            }
            if (markRead) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE messages SET is_read = TRUE " +
                        "WHERE sender_id = ? AND receiver_id = ? " +
                        "  AND is_read = FALSE")) {
                    AuthService.setUuid(ps, 1, currentFriend.userId, c);
                    AuthService.setUuid(ps, 2, AuthService.Session.userId, c);
                    ps.executeUpdate();
                }
            }
            if (!msgData.isEmpty())
                messageList.scrollTo(msgData.size() - 1);
        } catch (Exception e) {
            setStatus("Error loading messages: " + e.getMessage(), true);
        }
    }

    // ── SEND TEXT ────────────────────────────────────────────────────────
    @FXML
    private void sendMessage() {
        if (currentFriend == null) {
            setStatus("Select a friend to chat with first.", true);
            return;
        }
        String text = messageField == null ? "" : messageField.getText().trim();
        if (text.isBlank()) return;
        if (text.startsWith(ATTACH_PREFIX)) {
            setStatus("Message may not start with the attachment tag.", true);
            return;
        }
        insertMessage(text);
        if (messageField != null) messageField.clear();
    }

    // ── SEND ATTACHMENT: PDF, DOCX, images (per requirement) ─────────────
    @FXML
    private void attachFile() {
        if (currentFriend == null) {
            setStatus("Select a friend to chat with first.", true);
            return;
        }

        // KLC v1.0 safeguarding: students may not send attachments by
        // default (config: social.allow_student_attachments=true to lift).
        if ("STUDENT".equals(AuthService.Session.role)
                && !com.femzyk.klc.util.ConfigService.flag(
                    "social.allow_student_attachments", false)) {
            setStatus("Attachments from student accounts are disabled. "
                + "Ask the school administrator if you need this.", true);
            return;
        }

        FileChooser fc = new FileChooser();
        fc.setTitle("Attach File (PDF, DOCX or Image)");
        fc.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter(
                "Documents and Images",
                "*.pdf", "*.docx", "*.doc",
                "*.jpg", "*.jpeg", "*.png"),
            new FileChooser.ExtensionFilter("PDF Files", "*.pdf"),
            new FileChooser.ExtensionFilter("Word Documents", "*.docx", "*.doc"),
            new FileChooser.ExtensionFilter("Images", "*.jpg", "*.jpeg", "*.png"));
        File f = fc.showOpenDialog(messageList.getScene().getWindow());
        if (f == null) return;

        // 15 MB cap - keeps DB paths sane and copies fast
        if (f.length() > 15L * 1024 * 1024) {
            setStatus("File too large (max 15 MB).", true);
            return;
        }
        try {
            File dir = new File("klc_assets/attachments");
            dir.mkdirs();
            String safeName = System.currentTimeMillis() + "_" +
                f.getName().replaceAll("[^A-Za-z0-9._-]", "_");
            File dest = new File(dir, safeName);
            Files.copy(f.toPath(), dest.toPath(),
                StandardCopyOption.REPLACE_EXISTING);

            // KLC v1.0 FIX: upload to Supabase Storage so the receiver on
            // ANOTHER computer can actually open the file. Falls back to
            // the local path when offline/unconfigured.
            String remote = com.femzyk.klc.util.StorageService.upload(
                dest, AuthService.Session.userId);
            String body = (remote != null)
                ? ATTACH_PREFIX + " " + remote
                : ATTACH_PREFIX + " " + dest.getPath();

            insertMessage(body);
            setStatus(remote != null
                ? "Attachment sent (uploaded to school cloud): " + f.getName()
                : "Attachment sent (saved on this computer only - "
                  + "cloud upload unavailable offline): " + f.getName(),
                false);
        } catch (Exception e) {
            setStatus("Attachment error: " + e.getMessage(), true);
        }
    }

    private void insertMessage(String content) {
        try (Connection c = DatabaseManager.getConnection()) {
            if (!isFriend(c, currentFriend.userId)) {
                setStatus("You can only message ACCEPTED friends.", true);
                return;
            }

            // KLC v1.0 safeguarding 1: students may only message other
            // STUDENTS by default (config:
            // social.allow_student_staff_dm=true to lift).
            if ("STUDENT".equals(AuthService.Session.role)
                    && !com.femzyk.klc.util.ConfigService.flag(
                        "social.allow_student_staff_dm", false)
                    && !"STUDENT".equals(roleOf(c, currentFriend.userId))) {
                setStatus("Students cannot message staff accounts. "
                    + "Please contact your teacher through the school office.",
                    true);
                return;
            }

            // KLC v1.0 safeguarding 2: chat is locked school-wide while an
            // exam window is active (prevents exam-time collusion).
            if (isExamWindowActive(c)) {
                setStatus("Chat is locked while an exam is in progress. "
                    + "Close this screen - good luck!", true);
                return;
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO messages(id, sender_id, receiver_id, " +
                    "content, is_read) VALUES(?,?,?,?,FALSE)")) {
                AuthService.setUuid(ps, 1, UUID.randomUUID().toString(), c);
                AuthService.setUuid(ps, 2, AuthService.Session.userId, c);
                AuthService.setUuid(ps, 3, currentFriend.userId, c);
                ps.setString(4, content);
                ps.executeUpdate();
            }
            // Safeguarding audit trail: metadata only (who messaged whom,
            // attachment or not, size) - message CONTENT is never copied
            // into audit_logs.
            boolean isAttachment = content.startsWith(ATTACH_PREFIX);
            AuthService.logAudit(isAttachment
                    ? "MESSAGE_ATTACHMENT" : "MESSAGE_SEND",
                "messages", currentFriend.userId);
            loadMessages(false);
        } catch (Exception e) {
            setStatus("Send error: " + e.getMessage(), true);
        }
    }

    /** Role of another user (for the student/staff safeguarding gate). */
    private String roleOf(Connection c, String otherUserId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT role FROM users WHERE id = ?")) {
            AuthService.setUuid(ps, 1, otherUserId, c);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString(1) : "";
        }
    }

    /** True when ANY exam is inside its scheduled window right now. */
    private boolean isExamWindowActive(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM exams " +
                "WHERE is_active = TRUE " +
                "  AND start_at IS NOT NULL AND end_at IS NOT NULL " +
                "  AND start_at <= CURRENT_TIMESTAMP " +
                "  AND end_at   >= CURRENT_TIMESTAMP")) {
            ResultSet rs = ps.executeQuery();
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    private void openAttachment(String path) {
        try {
            File f;
            if (path.startsWith("http")) {
                // KLC v1.0: remote attachment - download from school
                // cloud storage, then open the local copy.
                String hint = new File(path).getName();
                if (hint.length() > 40) hint = hint.substring(hint.length() - 40);
                f = com.femzyk.klc.util.StorageService.download(path, hint);
                if (f == null) {
                    setStatus("Could not download the file (you may be "
                        + "offline). Try again when connected.", true);
                    return;
                }
            } else {
                f = new File(path);
            }
            if (!f.exists()) {
                setStatus("File not found: " + f.getName() +
                    " (it may be on the sender's computer only).", true);
                return;
            }
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(f);
            } else {
                setStatus("Saved at: " + f.getAbsolutePath(), false);
            }
        } catch (Exception e) {
            setStatus("Cannot open: " + e.getMessage(), true);
        }
    }

    @FXML
    private void refreshChat() {
        loadFriends();
        if (currentFriend != null) loadMessages(true);
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

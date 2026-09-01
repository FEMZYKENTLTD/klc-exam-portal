package com.femzyk.klc.admin;

import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.db.DatabaseManager;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * NotificationController v1.0
 *
 * FIX HISTORY (this revision):
 * 1. notifyResults(): REMOVED H2-only "SELECT RANDOM_UUID()" SQL that
 *    fails on Supabase PostgreSQL ("function random_uuid() does not
 *    exist"). Now selects student emails and inserts each row with a
 *    Java-generated UUID through AuthService.setUuid - works identically
 *    on H2 and PostgreSQL.
 * 2. autoApprovePendingAnnouncements(): REPLACED SQL INTERVAL syntax with
 *    a Java-computed 3-day cutoff Timestamp parameter - dialect-safe on
 *    both databases.
 * 3. KEPT: teacher PENDING rule, admin instant APPROVED, no-status-column
 *    fallback, setUuid for all UUID binds, all existing features.
 *
 * REQUIRES: announcements.status column
 * (migration 01_klc_migration_v1_0.sql). Falls back gracefully if the
 * column is missing.
 */
public class NotificationController {

    @FXML private TextField titleField;
    @FXML private TextArea  messageArea;
    @FXML private ComboBox<String> targetRoleBox;
    @FXML private Label     statusLabel;
    @FXML private TableView<NotificationRow> table;
    @FXML private TableColumn<NotificationRow, String> colTo, colSubject,
                                                       colStatus, colDate;

    private final ObservableList<NotificationRow> data =
        FXCollections.observableArrayList();

    public static class NotificationRow {
        String id, recipient, subject, status, date;

        NotificationRow(String id, String r, String s, String st, String d) {
            this.id = id; recipient = r; subject = s; status = st; date = d;
        }

        public String getTo()      { return recipient == null ? "-" : recipient; }
        public String getSubject() { return subject; }
        public String getStatus()  { return status; }
        public String getDate()    { return date; }
    }

    @FXML
    public void initialize() {
        targetRoleBox.getItems().addAll(
            "ALL","STUDENT","TEACHER","PRINCIPAL_ADMIN","EXAM_OFFICER");
        targetRoleBox.setValue("ALL");

        colTo.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(c.getValue().getTo()));
        colSubject.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getSubject()));
        colStatus.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getStatus()));
        colDate.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getDate()));

        table.setItems(data);
        loadNotifications();
    }

    // =========================================================================
    //  SEND / QUEUE ANNOUNCEMENT WITH TEACHER RULE
    //  Teacher sends -> status PENDING. Admin/Principal -> APPROVED.
    // =========================================================================
    @FXML
    private void sendTest() {
        String title   = titleField.getText().trim();
        String message = messageArea.getText().trim();
        String target  = targetRoleBox.getValue();

        if (title.isBlank() || message.isBlank()) {
            setStatus("Title and Message are required", true);
            return;
        }

        boolean isTeacher =
            "TEACHER".equalsIgnoreCase(AuthService.Session.role);
        String initialStatus = isTeacher ? "PENDING" : "APPROVED";

        try (Connection c = DatabaseManager.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO announcements(" +
                    "id, title, body, target_role, created_by, status) " +
                    "VALUES(?,?,?,?,?,?)")) {
                AuthService.setUuid(ps, 1, UUID.randomUUID().toString(), c);
                ps.setString(2, title);
                ps.setString(3, message);
                ps.setString(4, target);
                AuthService.setUuid(ps, 5, AuthService.Session.userId, c);
                ps.setString(6, initialStatus);
                ps.executeUpdate();
            }

            if (isTeacher) {
                setStatus(
                    "Announcement submitted for approval (Status: PENDING). " +
                    "Auto-approves after 3 days if not reviewed.", false);
            } else {
                setStatus("Announcement published to: " + target, false);
            }
            clearForm();
            loadNotifications();

        } catch (Exception e) {
            // Fallback for databases where the status column is not
            // migrated yet - announcement still goes out.
            try (Connection c2 = DatabaseManager.getConnection();
                 PreparedStatement ps = c2.prepareStatement(
                    "INSERT INTO announcements(" +
                    "id, title, body, target_role, created_by) " +
                    "VALUES(?,?,?,?,?)")) {
                AuthService.setUuid(ps, 1, UUID.randomUUID().toString(), c2);
                ps.setString(2, title);
                ps.setString(3, message);
                ps.setString(4, target);
                AuthService.setUuid(ps, 5, AuthService.Session.userId, c2);
                ps.executeUpdate();
                setStatus("Announcement sent to: " + target, false);
                clearForm();
                loadNotifications();
            } catch (Exception ex) {
                setStatus("Error: " + ex.getMessage(), true);
            }
        }
    }

    // =========================================================================
    //  3-DAY AUTO-APPROVAL (Priority 3 #13)
    //  Java-computed cutoff timestamp - identical behaviour on H2 and
    //  PostgreSQL. Runs every time notifications are loaded.
    // =========================================================================
    private void autoApprovePendingAnnouncements(Connection c) {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE announcements " +
                "SET status = 'APPROVED' " +
                "WHERE status = 'PENDING' AND created_at < ?")) {
            Timestamp cutoff = new Timestamp(
                System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000);
            ps.setTimestamp(1, cutoff);
            int n = ps.executeUpdate();
            if (n > 0) {
                System.out.println(
                    "[Announcements] Auto-approved " + n +
                    " pending announcement(s) older than 3 days");
            }
        } catch (Exception ignored) {
            // Status column not migrated yet - nothing to auto-approve
        }
    }

    // =========================================================================
    //  MANUAL APPROVAL (Admin/Principal reviews teacher submissions)
    // =========================================================================
    @FXML
    private void approvePending() {
        String role = AuthService.Session.role;
        if (!"SUPER_ADMIN".equals(role) && !"PRINCIPAL_ADMIN".equals(role)) {
            setStatus("Only admins can approve announcements", true);
            return;
        }
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE announcements SET status='APPROVED' " +
                 "WHERE status='PENDING'")) {
            int n = ps.executeUpdate();
            setStatus(n == 0
                ? "No pending announcements to approve"
                : "Approved " + n + " pending announcement(s)", false);
            loadNotifications();
        } catch (Exception e) {
            setStatus("Error: " + e.getMessage(), true);
        }
    }

    // =========================================================================
    //  NOTIFY ALL ACTIVE STUDENTS
    //  FIX: cross-database - UUIDs generated in Java, not SQL.
    // =========================================================================
    @FXML
    private void notifyResults() {
        String title   = titleField.getText().trim();
        String message = messageArea.getText().trim();

        if (title.isBlank()) {
            setStatus("Enter a title before notifying", true);
            return;
        }
        String body = message.isBlank() ? title : message;

        try (Connection c = DatabaseManager.getConnection()) {

            // 1. Collect active student emails
            List<String> emails = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT email FROM users " +
                    "WHERE role = 'STUDENT' AND is_active = TRUE " +
                    "AND email IS NOT NULL");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String em = rs.getString(1);
                    if (em != null && !em.isBlank()) emails.add(em);
                }
            }

            if (emails.isEmpty()) {
                setStatus("No active students with email addresses found", true);
                return;
            }

            // 2. Queue one notification per student with a Java UUID
            int count = 0;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO notification_queue(" +
                    "id, recipient_email, subject, body, status) " +
                    "VALUES(?,?,?,?, 'PENDING')")) {
                for (String em : emails) {
                    AuthService.setUuid(ps, 1,
                        UUID.randomUUID().toString(), c);
                    ps.setString(2, em);
                    ps.setString(3, title);
                    ps.setString(4, body);
                    ps.addBatch();
                    count++;
                    if (count % 100 == 0) ps.executeBatch();
                }
                ps.executeBatch();
            }

            setStatus("Queued notifications for " + count + " students", false);
            loadNotifications();

        } catch (Exception e) {
            setStatus("Error: " + e.getMessage(), true);
        }
    }

    // =========================================================================
    //  FLUSH QUEUE
    // =========================================================================
    @FXML
    private void flushQueue() {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE notification_queue " +
                 "SET status='SENT', sent_at=CURRENT_TIMESTAMP " +
                 "WHERE status='PENDING'")) {
            int n = ps.executeUpdate();
            setStatus("Marked " + n + " messages as sent", false);
            loadNotifications();
        } catch (Exception e) {
            setStatus("Error: " + e.getMessage(), true);
        }
    }

    // =========================================================================
    //  LOAD NOTIFICATION QUEUE TABLE
    //  Method name matches FXML onAction="#loadNotifications"
    // =========================================================================
    @FXML
    public void loadNotifications() {
        data.clear();
        try (Connection c = DatabaseManager.getConnection()) {
            autoApprovePendingAnnouncements(c);

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, recipient_email, subject, status, created_at " +
                    "FROM notification_queue " +
                    "ORDER BY created_at DESC LIMIT 100");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp(5);
                    data.add(new NotificationRow(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        ts == null ? "-" : ts.toLocalDateTime().toString()
                    ));
                }
            }
        } catch (Exception e) {
            setStatus("Queue load error: " + e.getMessage(), true);
        }
    }

    private void clearForm() {
        titleField.clear();
        if (messageArea != null) messageArea.clear();
        targetRoleBox.setValue("ALL");
    }

    private void setStatus(String msg, boolean error) {
        if (statusLabel == null) return;
        statusLabel.setText(msg);
        statusLabel.setStyle(error
            ? "-fx-text-fill:#ef4444; -fx-font-weight:bold;"
            : "-fx-text-fill:#0f7a3a; -fx-font-weight:bold;");
    }
}

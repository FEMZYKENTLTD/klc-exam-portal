package com.femzyk.klc.admin;

import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.db.DatabaseManager;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.sql.*;
import java.util.UUID;

public class NotificationController {

    @FXML private TextField    titleField;
    @FXML private TextArea     messageArea;
    @FXML private ComboBox<String> targetRoleBox;
    @FXML private Label        statusLabel;
    @FXML private TableView<NotificationRow>           table;
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
    //  SEND / QUEUE ANNOUNCEMENT
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

        try (Connection c = DatabaseManager.getConnection()) {
            // Insert announcement
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO announcements(" +
                    "id, title, body, target_role, created_by) " +
                    "VALUES(?,?,?,?,?)")) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, title);
                ps.setString(3, message);
                ps.setString(4, target);
                ps.setString(5, AuthService.Session.userId);
                ps.executeUpdate();
            }

            setStatus("Announcement sent to: " + target, false);
            clearForm();
            loadNotifications();

        } catch (Exception e) {
            setStatus("Error: " + e.getMessage(), true);
        }
    }

    // =========================================================================
    //  NOTIFY ALL STUDENTS WITH RECENT RESULTS
    // =========================================================================
    @FXML
    private void notifyResults() {
        String title   = titleField.getText().trim();
        String message = messageArea.getText().trim();

        if (title.isBlank()) {
            setStatus("Enter a title before notifying", true);
            return;
        }

        try (Connection c = DatabaseManager.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO notification_queue(" +
                    "id, recipient_email, subject, body, status) " +
                    "SELECT RANDOM_UUID(), u.email, ?, ?, 'PENDING' " +
                    "FROM users u " +
                    "WHERE u.role = 'STUDENT' AND u.is_active = TRUE")) {
                ps.setString(1, title);
                ps.setString(2, message.isBlank() ? title : message);
                int count = ps.executeUpdate();
                setStatus("Queued notifications for " + count + " students",
                    false);
            }
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
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT id, recipient_email, subject, status, created_at " +
                 "FROM notification_queue " +
                 "ORDER BY created_at DESC LIMIT 100")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Timestamp ts = rs.getTimestamp(5);
                data.add(new NotificationRow(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    ts == null ? "-"
                        : ts.toLocalDateTime().toString()
                ));
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
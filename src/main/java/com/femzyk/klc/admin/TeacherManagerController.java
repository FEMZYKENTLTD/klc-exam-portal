package com.femzyk.klc.admin;

import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.db.DatabaseManager;
import at.favre.lib.crypto.bcrypt.BCrypt;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.sql.*;
import java.util.UUID;

public class TeacherManagerController {

    @FXML private TableView<TeacherRow>              table;
    @FXML private TableColumn<TeacherRow, String>    colName, colEmail,
                                                      colRole, colSubjects, colStatus;
    @FXML private ComboBox<String>                    subjectAssignBox, classAssignBox;
    @FXML private Label                               status;

    private final ObservableList<TeacherRow> data =
            FXCollections.observableArrayList();
    private java.util.Map<String, String> subjectMap =
            new java.util.HashMap<>();

    public static class TeacherRow {
        String id, name, email, role, subjects, active;

        TeacherRow(String id, String n, String e,
                   String r, String s, boolean a) {
            this.id = id; name = n; email = e;
            role = r; subjects = s;
            active = a ? "Active" : "Inactive";
        }

        public String getName()     { return name; }
        public String getEmail()    { return email; }
        public String getRole()     { return role; }
        public String getSubjects() { return subjects; }
        public String getStatus()   { return active; }
    }

    @FXML
    public void initialize() {
        colName.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(c.getValue().getName()));
        colEmail.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(c.getValue().getEmail()));
        colRole.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(c.getValue().getRole()));
        colSubjects.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(c.getValue().getSubjects()));
        colStatus.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(c.getValue().getStatus()));

        table.setItems(data);

        classAssignBox.getItems().addAll(
            "JSS1","JSS2","JSS3","SS1","SS2","SS3");

        loadSubjectsForAssign();
        load();
    }

    private void loadSubjectsForAssign() {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT id, subject_code, subject_name FROM subjects " +
                 "WHERE is_active = TRUE ORDER BY subject_name")) {
            ResultSet rs = ps.executeQuery();
            subjectAssignBox.getItems().clear();
            subjectMap.clear();
            while (rs.next()) {
                String display = rs.getString(2) + " - " + rs.getString(3);
                subjectAssignBox.getItems().add(display);
                subjectMap.put(display, rs.getString(1));
            }
        } catch (Exception ignored) {}
    }

    @FXML
    public void load() {
        data.clear();
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT u.id, u.full_name, u.email, u.role, " +
                 "u.is_active, " +
                 "COALESCE(STRING_AGG(s.subject_code, ', '), 'None') AS subjects " +
                 "FROM users u " +
                 "LEFT JOIN teacher_subjects ts ON ts.teacher_id = u.id " +
                 "LEFT JOIN subjects s ON s.id = ts.subject_id " +
                 "WHERE u.role IN ('TEACHER','EXAM_OFFICER','PRINCIPAL_ADMIN') " +
                 "GROUP BY u.id, u.full_name, u.email, u.role, u.is_active " +
                 "ORDER BY u.role, u.full_name")) {

            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                data.add(new TeacherRow(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(6),
                    rs.getBoolean(5)
                ));
            }

            if (status != null)
                status.setText("Loaded " + data.size() + " staff accounts");

        } catch (Exception e) {
            // H2 doesn't support STRING_AGG - use fallback
            loadH2Fallback();
        }
    }

    private void loadH2Fallback() {
        data.clear();
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT u.id, u.full_name, u.email, u.role, u.is_active " +
                 "FROM users u " +
                 "WHERE u.role IN ('TEACHER','EXAM_OFFICER','PRINCIPAL_ADMIN') " +
                 "ORDER BY u.role, u.full_name")) {

            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String uid = rs.getString(1);
                // Get subjects for this teacher
                String subjects = getTeacherSubjects(c, uid);
                data.add(new TeacherRow(
                    uid,
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    subjects,
                    rs.getBoolean(5)
                ));
            }

            if (status != null)
                status.setText("Loaded " + data.size() + " staff accounts");

        } catch (Exception e) {
            if (status != null)
                status.setText("Error: " + e.getMessage());
        }
    }

    private String getTeacherSubjects(Connection c, String teacherId) {
        StringBuilder sb = new StringBuilder();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT s.subject_code FROM teacher_subjects ts " +
                "JOIN subjects s ON s.id = ts.subject_id " +
                "WHERE ts.teacher_id = ? ORDER BY s.subject_code")) {
            ps.setString(1, teacherId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(rs.getString(1));
            }
        } catch (Exception ignored) {}
        return sb.length() > 0 ? sb.toString() : "None assigned";
    }

    @FXML
    private void assignSubject() {
        TeacherRow r = table.getSelectionModel().getSelectedItem();
        if (r == null) {
            status.setText("Select a teacher first");
            return;
        }
        String subjectDisplay = subjectAssignBox.getValue();
        String cls            = classAssignBox.getValue();
        if (subjectDisplay == null) {
            status.setText("Select a subject to assign");
            return;
        }

        String subjectId = subjectMap.get(subjectDisplay);
        if (subjectId == null) {
            status.setText("Subject not found");
            return;
        }

        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO teacher_subjects(" +
                 "  id, teacher_id, subject_id, class_level, assigned_by) " +
                 "VALUES(?,?,?,?,?)")) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, r.id);
            ps.setString(3, subjectId);
            ps.setString(4, cls);
            ps.setString(5, AuthService.Session.userId);
            ps.executeUpdate();

            AuthService.logAudit("SUBJECT_ASSIGN", "teacher_subjects", r.id);
            status.setText("Subject assigned to " + r.name);
            load();

        } catch (Exception e) {
            status.setText("Error (may already be assigned): " + e.getMessage());
        }
    }

    @FXML
    private void resetPassword() {
        TeacherRow r = table.getSelectionModel().getSelectedItem();
        if (r == null) { status.setText("Select a teacher"); return; }

        if (!AuthService.isSuperAdmin()) {
            new Alert(Alert.AlertType.WARNING,
                "Only Super Admin can reset passwords").show();
            return;
        }

        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE users SET password_hash=?, " +
                 "failed_login_attempts=0, locked_until=NULL WHERE id=?")) {
            String hash = BCrypt.withDefaults()
                .hashToString(12, "Teacher123".toCharArray());
            ps.setString(1, hash);
            ps.setString(2, r.id);
            ps.executeUpdate();
            status.setText("Password reset to Teacher123 for " + r.name);
            AuthService.logAudit("PASSWORD_RESET", "users", r.id);
        } catch (Exception e) {
            status.setText("Error: " + e.getMessage());
        }
    }

    @FXML
    private void deactivate() {
        TeacherRow r = table.getSelectionModel().getSelectedItem();
        if (r == null) { status.setText("Select a teacher"); return; }

        if (!AuthService.isSuperAdmin()) {
            new Alert(Alert.AlertType.WARNING,
                "Only Super Admin can deactivate accounts").show();
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "Deactivate account for: " + r.name + "?\n" +
            "They will not be able to login until reactivated.",
            ButtonType.YES, ButtonType.NO);
        confirm.showAndWait().ifPresent(btn -> {
            if (btn != ButtonType.YES) return;
            try (Connection c = DatabaseManager.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                     "UPDATE users SET is_active = NOT is_active WHERE id=?")) {
                ps.setString(1, r.id);
                ps.executeUpdate();
                status.setText("Account toggled for: " + r.name);
                AuthService.logAudit("ACCOUNT_TOGGLE", "users", r.id);
                load();
            } catch (Exception e) {
                status.setText("Error: " + e.getMessage());
            }
        });
    }
}
package com.femzyk.klc.admin;

import at.favre.lib.crypto.bcrypt.BCrypt;
import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.db.DatabaseManager;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.sql.*;
import java.util.UUID;

public class ManualUserCreationController {

    // Create form
    @FXML private TextField    fullNameField, emailField, phoneField,
                                surnameField, admissionField;
    @FXML private ComboBox<String> roleBox, classBox, armBox;
    @FXML private Label        statusLabel;

    // Users table
    @FXML private TableView<UserRow>              usersTable;
    @FXML private TableColumn<UserRow, String>    colUName, colUEmail,
                                                   colURole, colUStatus;

    private final ObservableList<UserRow> usersData =
            FXCollections.observableArrayList();

    public static class UserRow {
        String id, name, email, role, status;
        UserRow(String id, String n, String e, String r, String s) {
            this.id = id; name = n; email = e; role = r; status = s;
        }
        public String getName()   { return name; }
        public String getEmail()  { return email; }
        public String getRole()   { return role; }
        public String getStatus() { return status; }
    }

    @FXML
    public void initialize() {
        if (!AuthService.isSuperAdmin()) {
            if (statusLabel != null)
                statusLabel.setText(
                    "Access denied. Super Admin only.");
            return;
        }

        roleBox.getItems().addAll(
            "STUDENT","TEACHER","EXAM_OFFICER",
            "PRINCIPAL_ADMIN","SUPER_ADMIN");
        roleBox.setValue("STUDENT");

        if (classBox != null)
            classBox.getItems().addAll(
                "JSS1","JSS2","JSS3","SS1","SS2","SS3");
        if (armBox != null)
            armBox.getItems().addAll(
                "A","B","C","Science","Art","Commercial");

        // Show/hide student fields based on role
        roleBox.valueProperty().addListener((o, ov, nv) ->
            updateFieldVisibility(nv));
        updateFieldVisibility("STUDENT");

        if (colUName != null) {
            colUName.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(
                    c.getValue().getName()));
            colUEmail.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(
                    c.getValue().getEmail()));
            colURole.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(
                    c.getValue().getRole()));
            colUStatus.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(
                    c.getValue().getStatus()));
            if (usersTable != null)
                usersTable.setItems(usersData);
        }

        loadUsers();
    }

    private void updateFieldVisibility(String role) {
        boolean isStudent = "STUDENT".equals(role);
        if (surnameField    != null) surnameField.setDisable(!isStudent);
        if (admissionField  != null) admissionField.setDisable(!isStudent);
        if (classBox        != null) classBox.setDisable(!isStudent);
        if (armBox          != null) armBox.setDisable(!isStudent);
    }

    private void loadUsers() {
        usersData.clear();
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT id, full_name, email, role, " +
                 "CASE WHEN is_active THEN 'Active' ELSE 'Inactive' END " +
                 "FROM users ORDER BY role, full_name LIMIT 200")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                usersData.add(new UserRow(
                    rs.getString(1), rs.getString(2),
                    rs.getString(3), rs.getString(4), rs.getString(5)));
            }
        } catch (Exception e) {
            if (statusLabel != null)
                statusLabel.setText("Load error: " + e.getMessage());
        }
    }

    @FXML
    private void createUser() {
        if (!AuthService.isSuperAdmin()) {
            setStatus("Access denied. Super Admin only.", true);
            return;
        }

        String fullName = fullNameField.getText().trim();
        String email    = emailField.getText().trim().toLowerCase();
        String phone    = phoneField != null
                        ? phoneField.getText().trim() : "";
        String role     = roleBox.getValue();

        if (fullName.isBlank() || email.isBlank() || role == null) {
            setStatus("Full Name, Email and Role are required.", true);
            return;
        }
        if (!email.contains("@")) {
            setStatus("Please enter a valid email address.", true);
            return;
        }

        try (Connection c = DatabaseManager.getConnection()) {
            // Check email not already taken
            try (PreparedStatement chk = c.prepareStatement(
                    "SELECT COUNT(*) FROM users WHERE LOWER(email)=?")) {
                chk.setString(1, email);
                ResultSet rs = chk.executeQuery();
                if (rs.next() && rs.getInt(1) > 0) {
                    setStatus("Email already registered: " + email, true);
                    return;
                }
            }

            String userId = UUID.randomUUID().toString();

            // Temp password: SURNAME+CLASS in caps for students,
            // SURNAME+ROLE for staff
            String surname  = surnameField != null
                            ? surnameField.getText().trim() : "";
            String cls      = classBox != null && classBox.getValue() != null
                            ? classBox.getValue() : "";
            String tempPass;
            if ("STUDENT".equals(role) && !surname.isBlank()) {
                tempPass = (surname + cls).toUpperCase()
                                         .replaceAll("\\s+","");
            } else {
                // Staff: first word of name + role
                String firstWord = fullName.split("\\s+")[0];
                tempPass = (firstWord + role).toUpperCase()
                                             .replaceAll("\\s+","");
            }

            String hash = BCrypt.withDefaults()
                .hashToString(12, tempPass.toCharArray());

            // Insert user
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO users(" +
                    "  id, full_name, email, phone, password_hash, " +
                    "  role, is_active, password_changed_at) " +
                    "VALUES(?,?,?,?,?,'"+role+"',TRUE,CURRENT_TIMESTAMP)")) {
                ps.setString(1, userId);
                ps.setString(2, fullName);
                ps.setString(3, email);
                ps.setString(4, phone.isBlank() ? null : phone);
                ps.setString(5, hash);
                ps.executeUpdate();
            }

            // If student - create profile
            if ("STUDENT".equals(role)) {
                String admNo = admissionField != null
                    && !admissionField.getText().isBlank()
                    ? admissionField.getText().trim()
                    : autoAdmissionNo(c, cls);

                String pin = (surname.isBlank() ? "KLC" : surname)
                    .toUpperCase().replaceAll("\\s+","") + cls;

                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO student_profiles(" +
                        "  id, user_id, admission_no, surname, " +
                        "  other_names, class_level, arm, session, " +
                        "  result_pin) " +
                        "VALUES(?,?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, UUID.randomUUID().toString());
                    ps.setString(2, userId);
                    ps.setString(3, admNo);
                    ps.setString(4, surname.isBlank()
                        ? fullName.split("\\s+")[0] : surname);
                    ps.setString(5, fullName);
                    ps.setString(6, cls.isBlank() ? null : cls);
                    ps.setString(7, armBox != null
                        ? armBox.getValue() : null);
                    ps.setString(8, "2024/2025");
                    ps.setString(9, pin);
                    ps.executeUpdate();
                }
            }

            AuthService.logAudit("MANUAL_USER_CREATE", "users", userId);

            setStatus(
                "User created!\n" +
                "Name: " + fullName + "\n" +
                "Email: " + email + "\n" +
                "Role: " + role + "\n" +
                "Temp Password: " + tempPass + "\n" +
                "Share this password privately. User should change on first login.",
                false);

            clearForm();
            loadUsers();

        } catch (Exception e) {
            e.printStackTrace();
            setStatus("Error: " + e.getMessage(), true);
        }
    }

    private String autoAdmissionNo(Connection c, String cls)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM student_profiles " +
                "WHERE class_level=?")) {
            ps.setString(1, cls);
            ResultSet rs = ps.executeQuery();
            rs.next();
            int n = rs.getInt(1) + 1;
            return "KLC/" + (cls.isBlank() ? "XX" : cls) +
                   "/" + String.format("%03d", n);
        }
    }

    @FXML
    private void resetSelectedPassword() {
        UserRow r = usersTable == null ? null
            : usersTable.getSelectionModel().getSelectedItem();
        if (r == null) {
            setStatus("Select a user from the table first.", true);
            return;
        }
        // New temp password = first word of name + RESET
        String firstWord = r.name.split("\\s+")[0];
        String newPass   = (firstWord + "RESET").toUpperCase();

        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE users SET password_hash=?, " +
                 "failed_login_attempts=0, locked_until=NULL " +
                 "WHERE id=?")) {
            String hash = BCrypt.withDefaults()
                .hashToString(12, newPass.toCharArray());
            ps.setString(1, hash);
            AuthService.setUuid(ps, 2, r.id, c);
            ps.executeUpdate();
            AuthService.logAudit("PASSWORD_RESET_ADMIN", "users", r.id);
            setStatus("Password reset for " + r.name +
                "\nNew temp password: " + newPass +
                "\nShare privately.", false);
        } catch (Exception e) {
            setStatus("Error: " + e.getMessage(), true);
        }
    }

    @FXML
    private void toggleSelectedActive() {
        UserRow r = usersTable == null ? null
            : usersTable.getSelectionModel().getSelectedItem();
        if (r == null) {
            setStatus("Select a user from the table first.", true);
            return;
        }
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE users SET is_active = NOT is_active WHERE id=?")) {
            AuthService.setUuid(ps, 1, r.id, c);
            ps.executeUpdate();
            AuthService.logAudit("ACCOUNT_TOGGLE", "users", r.id);
            setStatus("Account toggled for: " + r.name, false);
            loadUsers();
        } catch (Exception e) {
            setStatus("Error: " + e.getMessage(), true);
        }
    }

    @FXML
    private void refreshUsers() {
        loadUsers();
    }

    private void clearForm() {
        if (fullNameField   != null) fullNameField.clear();
        if (emailField      != null) emailField.clear();
        if (phoneField      != null) phoneField.clear();
        if (surnameField    != null) surnameField.clear();
        if (admissionField  != null) admissionField.clear();
        roleBox.setValue("STUDENT");
        if (classBox != null) classBox.setValue(null);
        if (armBox   != null) armBox.setValue(null);
    }

    private void setStatus(String msg, boolean error) {
        if (statusLabel == null) return;
        statusLabel.setText(msg);
        statusLabel.setStyle(error
            ? "-fx-text-fill:#c0392b; -fx-font-size:12px;"
            : "-fx-text-fill:#0f7a3a; -fx-font-size:12px;");
    }
}
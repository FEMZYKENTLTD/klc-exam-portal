package com.femzyk.klc.admin;

import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.db.DatabaseManager;
import com.femzyk.klc.util.BackupService;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.*;

public class SchoolSettingsController {

    @FXML private TextField   schoolNameField, principalField,
                               sessionField, logoPathField,
                               signaturePathField, campusField;
    @FXML private ComboBox<String> termBox;
    @FXML private TextArea    mottoArea;
    @FXML private Label       status;
    @FXML private ComboBox<String> fromClassBox, toClassBox;
    @FXML private TextField   announceTitle;

    // FIX: Changed from TextField to TextArea - matches FXML
    @FXML private TextArea    announceBody;

    @FXML private CheckBox    autoBackupCheck;

    @FXML
    public void initialize() {
        termBox.getItems().addAll("1st","2nd","3rd");
        fromClassBox.getItems().addAll(
            "JSS1","JSS2","JSS3","SS1","SS2","SS3");
        toClassBox.getItems().addAll(
            "JSS1","JSS2","JSS3","SS1","SS2","SS3","GRADUATED");
        loadProfile();
    }

    void loadProfile() {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT school_name, motto, principal_name, " +
                 "session_current, term_current, logo_url, " +
                 "principal_signature_url, " +
                 "COALESCE(campus_name,'') " +
                 "FROM school_profile LIMIT 1")) {
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                schoolNameField.setText(rs.getString(1));
                if (mottoArea != null) mottoArea.setText(rs.getString(2));
                principalField.setText(rs.getString(3));
                sessionField.setText(rs.getString(4));
                termBox.setValue(rs.getString(5));
                logoPathField.setText(
                    rs.getString(6) == null ? "" : rs.getString(6));
                signaturePathField.setText(
                    rs.getString(7) == null ? "" : rs.getString(7));
                if (campusField != null)
                    campusField.setText(rs.getString(8));
            }
        } catch (Exception e) {
            if (status != null) status.setText("Load error: " + e.getMessage());
        }
    }

    @FXML
    private void chooseLogo() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(
            new FileChooser.ExtensionFilter(
                "Images","*.png","*.jpg","*.jpeg"));
        File f = fc.showOpenDialog(schoolNameField.getScene().getWindow());
        if (f != null) {
            try {
                File dest = new File("klc_assets",
                    "klc_logo" + getExt(f.getName()));
                new File("klc_assets").mkdirs();
                Files.copy(f.toPath(), dest.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
                logoPathField.setText(dest.getPath());
                status.setText("Logo saved to " + dest.getPath());
            } catch (Exception e) {
                status.setText(e.getMessage());
            }
        }
    }

    @FXML
    private void chooseSignature() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(
            new FileChooser.ExtensionFilter(
                "Images","*.png","*.jpg","*.jpeg"));
        File f = fc.showOpenDialog(schoolNameField.getScene().getWindow());
        if (f != null) {
            try {
                File dest = new File("klc_assets",
                    "principal_signature" + getExt(f.getName()));
                new File("klc_assets").mkdirs();
                Files.copy(f.toPath(), dest.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
                signaturePathField.setText(dest.getPath());
                status.setText("Signature saved to " + dest.getPath());
            } catch (Exception e) {
                status.setText(e.getMessage());
            }
        }
    }

    private String getExt(String n) {
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(i) : ".png";
    }

    @FXML
    private void saveProfile() {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE school_profile SET " +
                 "school_name=?, motto=?, principal_name=?, " +
                 "session_current=?, term_current=?, " +
                 "logo_url=?, principal_signature_url=?, " +
                 "campus_name=?, " +
                 "updated_at=CURRENT_TIMESTAMP")) {
            ps.setString(1, schoolNameField.getText());
            ps.setString(2, mottoArea != null ? mottoArea.getText() : "");
            ps.setString(3, principalField.getText());
            ps.setString(4, sessionField.getText());
            ps.setString(5, termBox.getValue());
            ps.setString(6, logoPathField.getText().isBlank()
                ? null : logoPathField.getText());
            ps.setString(7, signaturePathField.getText().isBlank()
                ? null : signaturePathField.getText());
            ps.setString(8, campusField == null
                    || campusField.getText() == null
                    || campusField.getText().isBlank()
                ? null : campusField.getText().trim());
            ps.executeUpdate();
            status.setText(
                "School profile saved. Appears on all Report Cards, " +
                "Transcripts and ID Cards. No FEMZYK watermark.");
            status.setStyle("-fx-text-fill:#0f7a3a; -fx-font-weight:bold;");
        } catch (Exception e) {
            status.setText("Error: " + e.getMessage());
            status.setStyle("-fx-text-fill:#c0392b;");
        }
    }

    @FXML
    private void runRollover() {
        String from = fromClassBox.getValue();
        String to   = toClassBox.getValue();
        if (from == null || to == null) {
            status.setText("Select From and To class.");
            return;
        }
        if (!AuthService.isSuperAdmin()) {
            new Alert(Alert.AlertType.WARNING,
                "Only Super Admin can run Term Rollover.").show();
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "Promote ALL active students from " + from + " to " + to + "?",
            ButtonType.YES, ButtonType.NO);
        if (confirm.showAndWait().orElse(ButtonType.NO) != ButtonType.YES)
            return;

        try (Connection c = DatabaseManager.getConnection()) {
            int n;
            if ("GRADUATED".equals(to)) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE student_profiles " +
                        "SET status='GRADUATED' WHERE class_level=?")) {
                    ps.setString(1, from);
                    n = ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE student_profiles " +
                        "SET class_level=? " +
                        "WHERE class_level=? AND status='ACTIVE'")) {
                    ps.setString(1, to);
                    ps.setString(2, from);
                    n = ps.executeUpdate();
                }
            }
            AuthService.logAudit("TERM_ROLLOVER", "student_profiles", null);
            status.setText("Rollover complete: " + n +
                " students moved " + from + " to " + to);
            status.setStyle("-fx-text-fill:#0f7a3a; -fx-font-weight:bold;");
        } catch (Exception e) {
            status.setText("Error: " + e.getMessage());
        }
    }

    @FXML
    private void postAnnouncement() {
        String title = announceTitle.getText().trim();
        String body  = announceBody != null
            ? announceBody.getText().trim() : "";

        if (title.isBlank() || body.isBlank()) {
            status.setText("Title and message body are required.");
            return;
        }

        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO announcements(" +
                 "id, title, body, target_role, created_by) " +
                 "VALUES(?,?,?,'ALL',?)")) {
            ps.setString(1, java.util.UUID.randomUUID().toString());
            ps.setString(2, title);
            ps.setString(3, body);
            AuthService.setUuid(ps, 4, AuthService.Session.userId, c);
            ps.executeUpdate();
            status.setText(
                "Announcement posted. Students see it on their dashboard.");
            status.setStyle("-fx-text-fill:#0f7a3a; -fx-font-weight:bold;");
            announceTitle.clear();
            if (announceBody != null) announceBody.clear();
        } catch (Exception e) {
            status.setText("Error: " + e.getMessage());
        }
    }

    @FXML
    private void backupDb() {
        try {
            var r = BackupService.createBackup(AuthService.Session.userId);
            status.setText("Backup complete: " + r.file +
                "  " + String.format("%.2f MB", r.size / 1024.0 / 1024.0) +
                "  SHA256: " + r.sha256.substring(0, 16));
            new Alert(Alert.AlertType.INFORMATION,
                "Backup saved: " + r.file).show();
        } catch (Exception e) {
            status.setText("Backup failed: " + e.getMessage());
        }
    }

    @FXML
    private void rotateCodes() {
        if (!AuthService.isSuperAdmin()) {
            status.setText("Super Admin only.");
            return;
        }
        status.setText(
            "Codes in effect: SUPER_ADMIN = "
                + displayCode(AuthService.getCodeSuperAdmin()) + " | " +
            "TEACHER = " + displayCode(AuthService.getCodeAdmin()) + " | " +
            "STUDENT = " + displayCode(AuthService.getCodeStudent()) + ". " +
            "Set code.* in config.properties to change - takes effect at next " +
            "app start (no rebuild). See SECURITY_CREDENTIALS.md for rotation.");
    }

    private String displayCode(String code) {
        return (code == null || code.isBlank())
            ? "NOT SET (registration disabled)" : code;
    }
}

package com.femzyk.klc.social;

import at.favre.lib.crypto.bcrypt.BCrypt;
import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.db.DatabaseManager;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.*;
import java.time.LocalDate;
import java.time.Period;
import java.util.UUID;

/**
 * ProfileController - KLC CBT Suite v1.0 (G5 - NEW FEATURE)
 *
 * User Profile page for ALL roles:
 * - Profile photo (upload, stored in klc_assets/profile_photos/)
 * - Bio, Date of Birth (age auto-calculated), Address
 * - Change password (current + new + confirm, BCrypt verified,
 *   password_changed_at updated so the 90-day staff expiry works)
 *
 * Rule 11 notes:
 * - setUuid for every UUID bind (Rule 5)
 * - Self-creates user_profiles in H2 offline mode (dialect-aware)
 * - Upsert = UPDATE first, INSERT if 0 rows (cross-DB, same pattern
 *   as CAUploadController)
 * - Photo stored as local path (consistent with passport photos)
 */
public class ProfileController {

    @FXML private ImageView photoView;
    @FXML private Label     nameLabel, roleLabel, emailLabel, ageLabel;
    @FXML private TextArea  bioArea;
    @FXML private DatePicker dobPicker;
    @FXML private TextArea  addressArea;
    @FXML private Label     profileStatusLabel;

    @FXML private PasswordField currentPassField, newPassField, confirmPassField;
    @FXML private Label         passStatusLabel;

    private String photoPath = null;

    @FXML
    public void initialize() {
        ensureTable();

        nameLabel.setText(AuthService.Session.fullName);
        roleLabel.setText(displayRole(AuthService.Session.role));
        emailLabel.setText(AuthService.Session.email == null
            ? "-" : AuthService.Session.email);

        if (dobPicker != null) {
            dobPicker.valueProperty().addListener((o, ov, nv) -> updateAge(nv));
        }
        loadProfile();
    }

    private String displayRole(String r) {
        if (r == null) return "-";
        return switch (r) {
            case "SUPER_ADMIN"     -> "Super Administrator";
            case "PRINCIPAL_ADMIN" -> "Principal / Admin";
            case "EXAM_OFFICER"    -> "Exam Officer";
            case "TEACHER"         -> "Teacher";
            case "STUDENT"         -> "Student";
            default                -> r;
        };
    }

    /** H2 offline cache does not ship user_profiles - create if needed. */
    private void ensureTable() {
        try (Connection c = DatabaseManager.getConnection()) {
            boolean h2 = c.getMetaData().getDatabaseProductName()
                          .toLowerCase().contains("h2");
            if (h2) {
                try (Statement s = c.createStatement()) {
                    s.execute(
                        "CREATE TABLE IF NOT EXISTS user_profiles (" +
                        "  id            VARCHAR(36) DEFAULT RANDOM_UUID() PRIMARY KEY," +
                        "  user_id       VARCHAR(36) UNIQUE," +
                        "  photo_url     TEXT," +
                        "  bio           TEXT," +
                        "  date_of_birth DATE," +
                        "  address       TEXT," +
                        "  updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
                }
            }
        } catch (Exception ignored) {}
    }

    private void loadProfile() {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT photo_url, bio, date_of_birth, address " +
                 "FROM user_profiles WHERE user_id = ?")) {
            AuthService.setUuid(ps, 1, AuthService.Session.userId, c);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                photoPath = rs.getString(1);
                if (bioArea != null && rs.getString(2) != null)
                    bioArea.setText(rs.getString(2));
                Date dob = rs.getDate(3);
                if (dobPicker != null && dob != null) {
                    dobPicker.setValue(dob.toLocalDate());
                    updateAge(dob.toLocalDate());
                }
                if (addressArea != null && rs.getString(4) != null)
                    addressArea.setText(rs.getString(4));
                showPhoto();
            }
        } catch (Exception e) {
            setProfileStatus("Load error: " + e.getMessage(), true);
        }
    }

    private void updateAge(LocalDate dob) {
        if (ageLabel == null) return;
        if (dob == null) { ageLabel.setText("Age: -"); return; }
        int age = Period.between(dob, LocalDate.now()).getYears();
        ageLabel.setText("Age: " + age + " years");
    }

    private void showPhoto() {
        if (photoView == null || photoPath == null || photoPath.isBlank()) return;
        try {
            File f = new File(photoPath);
            if (f.exists())
                photoView.setImage(new Image(
                    f.toURI().toString(), 140, 160, true, true));
        } catch (Exception ignored) {}
    }

    @FXML
    private void choosePhoto() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select Profile Photo");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(
            "Images", "*.jpg", "*.jpeg", "*.png"));
        File f = fc.showOpenDialog(nameLabel.getScene().getWindow());
        if (f == null) return;
        try {
            File dir = new File("klc_assets/profile_photos");
            dir.mkdirs();
            String ext = f.getName().contains(".")
                ? f.getName().substring(f.getName().lastIndexOf('.'))
                : ".jpg";
            File dest = new File(dir,
                AuthService.Session.userId.replace("-", "") + ext);
            Files.copy(f.toPath(), dest.toPath(),
                StandardCopyOption.REPLACE_EXISTING);
            photoPath = dest.getPath();
            showPhoto();
            setProfileStatus(
                "Photo selected. Click Save Profile to keep it.", false);
        } catch (Exception e) {
            setProfileStatus("Photo error: " + e.getMessage(), true);
        }
    }

    @FXML
    private void saveProfile() {
        String bio = bioArea == null ? null : bioArea.getText();
        LocalDate dob = dobPicker == null ? null : dobPicker.getValue();
        String addr = addressArea == null ? null : addressArea.getText();

        try (Connection c = DatabaseManager.getConnection()) {
            int updated;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE user_profiles SET photo_url=?, bio=?, " +
                    "date_of_birth=?, address=?, updated_at=CURRENT_TIMESTAMP " +
                    "WHERE user_id=?")) {
                ps.setString(1, photoPath);
                ps.setString(2, bio);
                if (dob == null) ps.setNull(3, Types.DATE);
                else ps.setDate(3, Date.valueOf(dob));
                ps.setString(4, addr);
                AuthService.setUuid(ps, 5, AuthService.Session.userId, c);
                updated = ps.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO user_profiles(" +
                        "id, user_id, photo_url, bio, date_of_birth, address) " +
                        "VALUES(?,?,?,?,?,?)")) {
                    AuthService.setUuid(ps, 1, UUID.randomUUID().toString(), c);
                    AuthService.setUuid(ps, 2, AuthService.Session.userId, c);
                    ps.setString(3, photoPath);
                    ps.setString(4, bio);
                    if (dob == null) ps.setNull(5, Types.DATE);
                    else ps.setDate(5, Date.valueOf(dob));
                    ps.setString(6, addr);
                    ps.executeUpdate();
                }
            }
            AuthService.logAudit("PROFILE_UPDATE", "user_profiles",
                AuthService.Session.userId);
            setProfileStatus("Profile saved successfully.", false);
        } catch (Exception e) {
            setProfileStatus("Save error: " + e.getMessage(), true);
        }
    }

    // ── Change password ──────────────────────────────────────────────────
    @FXML
    private void changePassword() {
        String cur = currentPassField == null ? "" : currentPassField.getText();
        String nw  = newPassField    == null ? "" : newPassField.getText();
        String cf  = confirmPassField == null ? "" : confirmPassField.getText();

        if (cur.isBlank() || nw.isBlank() || cf.isBlank()) {
            setPassStatus("Fill in all three password fields.", true);
            return;
        }
        if (!nw.equals(cf)) {
            setPassStatus("New passwords do not match.", true);
            return;
        }
        if (nw.length() < 6) {
            setPassStatus("New password must be at least 6 characters.", true);
            return;
        }
        if (nw.equals(cur)) {
            setPassStatus("New password must be different from the current one.", true);
            return;
        }

        try (Connection c = DatabaseManager.getConnection()) {
            String hash = null;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT password_hash FROM users WHERE id = ?")) {
                AuthService.setUuid(ps, 1, AuthService.Session.userId, c);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) hash = rs.getString(1);
            }
            if (hash == null) {
                setPassStatus("Account not found.", true);
                return;
            }
            boolean ok = BCrypt.verifyer()
                .verify(cur.toCharArray(), hash).verified;
            if (!ok) {
                setPassStatus("Current password is incorrect.", true);
                return;
            }
            String newHash = BCrypt.withDefaults()
                .hashToString(12, nw.toCharArray());
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE users SET password_hash=?, " +
                    "password_changed_at=CURRENT_TIMESTAMP WHERE id=?")) {
                ps.setString(1, newHash);
                AuthService.setUuid(ps, 2, AuthService.Session.userId, c);
                ps.executeUpdate();
            }
            AuthService.logAudit("PASSWORD_CHANGE", "users",
                AuthService.Session.userId);
            currentPassField.clear();
            newPassField.clear();
            confirmPassField.clear();
            setPassStatus("Password changed successfully.", false);
        } catch (Exception e) {
            setPassStatus("Error: " + e.getMessage(), true);
        }
    }

    private void setProfileStatus(String m, boolean err) {
        if (profileStatusLabel == null) return;
        profileStatusLabel.setText(m);
        profileStatusLabel.setStyle(err
            ? "-fx-text-fill:#ef4444; -fx-font-weight:bold;"
            : "-fx-text-fill:#0f7a3a; -fx-font-weight:bold;");
    }

    private void setPassStatus(String m, boolean err) {
        if (passStatusLabel == null) return;
        passStatusLabel.setText(m);
        passStatusLabel.setStyle(err
            ? "-fx-text-fill:#ef4444; -fx-font-weight:bold;"
            : "-fx-text-fill:#0f7a3a; -fx-font-weight:bold;");
    }
}

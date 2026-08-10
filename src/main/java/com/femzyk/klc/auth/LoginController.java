package com.femzyk.klc.auth;

import com.femzyk.klc.MainApp;
import com.femzyk.klc.db.DatabaseManager;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.sql.*;

public class LoginController {

    @FXML private TextField     emailField;
    @FXML private PasswordField passField;
    @FXML private TextField     passVisible;
    @FXML private Button        togglePassBtn;
    @FXML private Label         statusLabel;
    @FXML private Label         totpLabel;
    @FXML private TextField     totpField;

    private String  pendingUserId = null;
    private boolean passShown     = false;

    // Eye icon unicode characters
    private static final String ICON_EYE_OPEN   = "\uD83D\uDC41";  // eye
    private static final String ICON_EYE_CLOSED  = "\uD83D\uDEAB"; // no entry (password hidden)

    // =========================================================================
    //  INITIALIZE
    // =========================================================================
    @FXML
    public void initialize() {
        if (passVisible != null) {
            passVisible.setManaged(false);
            passVisible.setVisible(false);
        }

        // Keep both fields in sync
        if (passField != null && passVisible != null) {
            passField.textProperty().addListener((obs, ov, nv) -> {
                if (!passShown) passVisible.setText(nv);
            });
            passVisible.textProperty().addListener((obs, ov, nv) -> {
                if (passShown) passField.setText(nv);
            });
        }

        // Set initial eye icon
        if (togglePassBtn != null) {
            togglePassBtn.setText(ICON_EYE_OPEN);
            togglePassBtn.setStyle(
                togglePassBtn.getStyle() +
                " -fx-font-size:16px;");
        }
    }

    // =========================================================================
    //  TOGGLE PASSWORD VISIBILITY - Eye Icon
    // =========================================================================
    @FXML
    private void togglePassword() {
        passShown = !passShown;

        if (passShown) {
            // Show plain text
            if (passVisible != null) {
                passVisible.setText(
                    passField != null ? passField.getText() : "");
                passVisible.setVisible(true);
                passVisible.setManaged(true);
            }
            if (passField != null) {
                passField.setVisible(false);
                passField.setManaged(false);
            }
            // Eye with slash = currently showing password
            if (togglePassBtn != null)
                togglePassBtn.setText(ICON_EYE_CLOSED);

        } else {
            // Show dots
            if (passField != null) {
                passField.setText(
                    passVisible != null ? passVisible.getText() : "");
                passField.setVisible(true);
                passField.setManaged(true);
            }
            if (passVisible != null) {
                passVisible.setVisible(false);
                passVisible.setManaged(false);
            }
            // Eye = currently hiding password (click to show)
            if (togglePassBtn != null)
                togglePassBtn.setText(ICON_EYE_OPEN);
        }
    }

    // =========================================================================
    //  HANDLE LOGIN
    // =========================================================================
    @FXML
    private void handleLogin() throws Exception {
        String email = emailField.getText() == null
                     ? "" : emailField.getText().trim();

        // Get password from whichever field is active
        String password = (passShown && passVisible != null)
                        ? passVisible.getText()
                        : (passField.getText() == null
                            ? "" : passField.getText());

        if (email.isBlank()) {
            setStatus("Please enter your email address.", true);
            emailField.requestFocus();
            return;
        }
        if (password.isBlank()) {
            setStatus("Please enter your password.", true);
            return;
        }

        // 2FA pending step
        if (pendingUserId != null) {
            handle2FA();
            return;
        }

        setStatus("Signing in...", false);

        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT id, full_name, password_hash, role, email, " +
                 "failed_login_attempts, locked_until, " +
                 "totp_enabled, password_changed_at " +
                 "FROM users " +
                 "WHERE LOWER(email) = LOWER(?) AND is_active = TRUE")) {

            ps.setString(1, email);
            ResultSet rs = ps.executeQuery();

            if (!rs.next()) {
                setStatus("No account found with that email address.\n" +
                          "Please register first or check your email.", true);
                return;
            }

            // Lockout check
            Timestamp locked = rs.getTimestamp("locked_until");
            if (locked != null &&
                    locked.getTime() > System.currentTimeMillis()) {
                long minsLeft = (locked.getTime() -
                                 System.currentTimeMillis()) / 60000;
                setStatus("Account locked. Try again in " +
                          minsLeft + " minute(s).", true);
                return;
            }

            // Verify password with BCrypt
            String  hash = rs.getString("password_hash");
            boolean ok   = false;
            try {
                ok = at.favre.lib.crypto.bcrypt.BCrypt
                         .verifyer()
                         .verify(password.toCharArray(), hash)
                         .verified;
            } catch (Exception ignored) {}

            String uid  = rs.getString("id");
            String role = rs.getString("role");

            if (!ok) {
                int fails = rs.getInt("failed_login_attempts") + 1;
                Timestamp lockUntil = (fails >= 5)
                    ? new Timestamp(
                        System.currentTimeMillis() + 15 * 60 * 1000L)
                    : null;

                try (PreparedStatement up = c.prepareStatement(
                        "UPDATE users SET failed_login_attempts=?, " +
                        "locked_until=? WHERE id=?")) {
                    up.setInt(1, fails);
                    up.setTimestamp(2, lockUntil);
                    AuthService.setUuid(up, 3, uid, c);
                    up.executeUpdate();
                }

                if (fails >= 5) {
                    setStatus("Account locked for 15 minutes " +
                              "(5 failed attempts).\n" +
                              "Use Forgot Password to reset.", true);
                } else {
                    setStatus("Incorrect password. Attempt " +
                              fails + " of 5.", true);
                }
                return;
            }

            // Password expiry check - staff only, not students
            if (!"STUDENT".equals(role)) {
                Timestamp pwdChanged = rs.getTimestamp("password_changed_at");
                if (pwdChanged != null) {
                    long days = (System.currentTimeMillis()
                                 - pwdChanged.getTime())
                                / (1000L * 60 * 60 * 24);
                    if (days > 90) {
                        setStatus("Your password has expired (over 90 days).\n" +
                                  "Please use Forgot Password to reset it.", true);
                        return;
                    }
                }
            }

            // Reset failed attempts on successful password
            try (PreparedStatement up = c.prepareStatement(
                    "UPDATE users SET failed_login_attempts=0, " +
                    "locked_until=NULL WHERE id=?")) {
                AuthService.setUuid(up, 1, uid, c);
                up.executeUpdate();
            }

            // 2FA check
            boolean totpEnabled = false;
            try { totpEnabled = rs.getBoolean("totp_enabled"); }
            catch (Exception ignored) {}

            if (totpEnabled) {
                pendingUserId = uid;
                AuthService.Session.fullName = rs.getString("full_name");
                AuthService.Session.email    = rs.getString("email");

                if (totpLabel != null) {
                    totpLabel.setVisible(true);
                    totpLabel.setManaged(true);
                }
                if (totpField != null) {
                    totpField.setVisible(true);
                    totpField.setManaged(true);
                    totpField.requestFocus();
                }
                if (emailField != null) emailField.setDisable(true);
                if (passField  != null) passField.setDisable(true);
                if (passVisible!= null) passVisible.setDisable(true);
                if (togglePassBtn != null) togglePassBtn.setDisable(true);

                setStatus("Enter the 6-digit code from your " +
                          "Authenticator app.", false);
                return;
            }

            // Successful login - set session
            AuthService.Session.userId   = uid;
            AuthService.Session.fullName = rs.getString("full_name");
            AuthService.Session.role     = role;
            AuthService.Session.email    = rs.getString("email");
            AuthService.Session.touch();
            AuthService.logAudit("LOGIN", "users", uid);

            goToDashboard(role);

        } catch (Exception e) {
            e.printStackTrace();
            String msg = e.getMessage() == null
                       ? "Unknown error" : e.getMessage();
            if (msg.contains("Table") && msg.contains("not found")) {
                setStatus("Database not ready.\n" +
                          "Please restart the application.", true);
            } else if (msg.contains("uuid") || msg.contains("UUID")) {
                setStatus("Database type error. Please restart the app.", true);
            } else {
                setStatus("Login error: " + msg, true);
            }
        }
    }

    // =========================================================================
    //  2FA STEP
    // =========================================================================
    private void handle2FA() {
        String code = totpField == null ? ""
                    : totpField.getText().trim();
        if (code.length() != 6) {
            setStatus("Enter the 6-digit code from your " +
                      "Authenticator app.", true);
            return;
        }
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT full_name, role, email FROM users WHERE id=?")) {
            AuthService.setUuid(ps, 1, pendingUserId, c);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                AuthService.Session.userId   = pendingUserId;
                AuthService.Session.fullName = rs.getString(1);
                AuthService.Session.role     = rs.getString(2);
                AuthService.Session.email    = rs.getString(3);
                AuthService.Session.touch();
                AuthService.logAudit("LOGIN_2FA", "users", pendingUserId);
                goToDashboard(rs.getString(2));
            }
        } catch (Exception e) {
            setStatus("2FA error: " + e.getMessage(), true);
        }
    }

    // =========================================================================
    //  NAVIGATION
    // =========================================================================
    private void goToDashboard(String role) throws Exception {
        if ("STUDENT".equals(role)) {
            MainApp.setRoot("student_dashboard.fxml", null);
        } else {
            MainApp.setRoot("admin_dashboard.fxml", null);
        }
    }

    @FXML private void goRegister() throws Exception {
        MainApp.setRoot("register.fxml", null);
    }

    @FXML private void goReset() throws Exception {
        MainApp.setRoot("password_reset.fxml", null);
    }

    // =========================================================================
    //  UTILITY
    // =========================================================================
    private void setStatus(String msg, boolean isError) {
        if (statusLabel == null) return;
        statusLabel.setText(msg);
        statusLabel.setStyle(isError
            ? "-fx-text-fill:#c0392b; -fx-font-size:13px;" +
              " -fx-font-weight:bold;"
            : "-fx-text-fill:#0f7a3a; -fx-font-size:13px;");
    }
}
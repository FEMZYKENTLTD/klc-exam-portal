package com.femzyk.klc.auth;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import com.femzyk.klc.MainApp;
import com.femzyk.klc.db.DatabaseManager;

import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

/**
 * RegisterController v1.0
 *
 * FIX HISTORY (this revision):
 * 1. RESTORED original FXML handler names that register.fxml wires to:
 *    handleRegister / choosePassport / captureWebcam / suggestAdmissionNo.
 *    (A previous revision renamed them, which made the Register page
 *    throw FXMLLoadException and fail to open.)
 * 2. RESTORED captureWebcam() - working webcam passport capture feature.
 * 3. RESTORED full updateVisibility() - student fields disable for staff,
 *    subject checklist disables for students/super admin.
 * 4. RESTORED teacher rule - staff must select at least ONE subject.
 * 5. KEPT the new Priority 2 #10 fields: phoneTypeBox (Personal/Parent/
 *    Guardian) and contactMethodBox (Call/WhatsApp/Both). Both are
 *    null-safe: the page still works before register.fxml adds them.
 * 6. NEW: phone type/contact now stored in users.phone_type and
 *    users.phone_contact for ALL roles (requires migration
 *    01_klc_migration_v1_0.sql). parent_phone stays a clean phone
 *    number - no bracket metadata mixed into it.
 */
public class RegisterController {

    @FXML private ComboBox<String> roleBox, classBox, armBox,
                                   genderBox, securityQuestionBox,
                                   phoneTypeBox, contactMethodBox;
    @FXML private TextField        nameField, emailField, codeField,
                                   admissionField, surnameField,
                                   parentPhoneField, securityAnswerField;
    @FXML private PasswordField    passField, passConfirmField;
    @FXML private Label            statusLabel, passStrengthLabel;
    @FXML private VBox             subjectCheckBoxContainer;
    @FXML private ImageView        passportPreview;
    @FXML private Label            passportPathLabel;

    private final Map<String, String> subjectMap    = new HashMap<>();
    private final ArrayList<CheckBox> subjectChecks = new ArrayList<>();
    private String passportFilePath = null;

    @FXML
    public void initialize() {
        roleBox.getItems().addAll(
            "STUDENT","TEACHER","EXAM_OFFICER",
            "PRINCIPAL_ADMIN","SUPER_ADMIN","PARENT");
        roleBox.setValue("STUDENT");

        classBox.getItems().addAll(
            "JSS1","JSS2","JSS3","SS1","SS2","SS3");
        armBox.getItems().addAll(
            "A","B","C","Science","Art","Commercial");

        if (genderBox != null)
            genderBox.getItems().addAll("Male","Female");

        if (securityQuestionBox != null) {
            securityQuestionBox.getItems().addAll(
                "What is your mother's maiden name?",
                "What was the name of your first primary school?",
                "In what city were you born?",
                "What is your favourite subject?",
                "What is your best friend's name?");
            securityQuestionBox.setValue(
                "What is your mother's maiden name?");
        }

        // Priority 2 #10 - Phone metadata (null-safe until FXML adds boxes)
        if (phoneTypeBox != null) {
            phoneTypeBox.getItems().addAll("Personal","Parent","Guardian");
            phoneTypeBox.setValue("Parent");
        }
        if (contactMethodBox != null) {
            contactMethodBox.getItems().addAll("Call","WhatsApp","Both");
            contactMethodBox.setValue("Both");
        }

        loadSubjects();

        roleBox.valueProperty().addListener(
            (o, a, b) -> updateVisibility());
        if (passField != null)
            passField.textProperty().addListener(
                (o, a, b) -> checkPasswordStrength());
        classBox.valueProperty().addListener((o, a, b) -> {
            if (b != null && admissionField != null
                    && admissionField.getText().isBlank())
                suggestAdmissionNo();
        });

        updateVisibility();
    }

    private void checkPasswordStrength() {
        String p = passField.getText() == null ? "" : passField.getText();
        String msg; String color = "#c0392b";
        if (p.length() < 6)       { msg = "Too short"; }
        else if (p.length() < 8)  { msg = "Weak"; }
        else if (p.matches(".*[A-Z].*") && p.matches(".*[0-9].*")
                && p.matches(".*[^A-Za-z0-9].*")) {
            msg = "Strong"; color = "#0f7a3a";
        } else if (p.matches(".*[A-Z].*") && p.matches(".*[0-9].*")) {
            msg = "Good";   color = "#d4a017";
        } else {
            msg = "Fair - add uppercase/number/symbol";
        }
        if (passStrengthLabel != null) {
            passStrengthLabel.setText("Password: " + msg);
            passStrengthLabel.setStyle("-fx-text-fill:" + color + ";");
        }
    }

    private void loadSubjects() {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT MIN(CAST(id AS VARCHAR(36))) AS id, subject_name " +
                 "FROM subjects " +
                 "WHERE is_active = TRUE " +
                 "GROUP BY subject_name " +
                 "ORDER BY subject_name")) {

            ResultSet rs = ps.executeQuery();
            subjectCheckBoxContainer.getChildren().clear();
            subjectChecks.clear();
            subjectMap.clear();

            while (rs.next()) {
                String id   = rs.getString("id");
                String name = rs.getString("subject_name");
                CheckBox cb = new CheckBox(name);
                cb.setUserData(id);
                cb.setStyle("-fx-font-size:13px;");
                subjectChecks.add(cb);
                subjectMap.put(id, name);
                subjectCheckBoxContainer.getChildren().add(cb);
            }

            if (subjectChecks.isEmpty()) {
                subjectCheckBoxContainer.getChildren().add(
                    new Label("No subjects found. Contact admin."));
            }

        } catch (Exception e) {
            e.printStackTrace();
            subjectCheckBoxContainer.getChildren().add(
                new Label("Error loading subjects: " + e.getMessage()));
        }
    }

    private void updateVisibility() {
        boolean isStudent = "STUDENT".equals(roleBox.getValue());
        boolean isParent  = "PARENT".equals(roleBox.getValue());
        boolean isStaff   = !isStudent && !isParent
            && !"SUPER_ADMIN".equals(roleBox.getValue());

        if (admissionField   != null) {
            // Students: their own admission no. Parents: their WARD's
            // admission no (links the parent to the child's results).
            admissionField.setDisable(!isStudent && !isParent);
            admissionField.setPromptText(isParent
                ? "Ward's admission no, e.g. KLC/SS2/045"
                : "e.g. KLC/SS2/045");
        }
        if (surnameField     != null) surnameField.setDisable(!isStudent);
        if (classBox         != null) classBox.setDisable(!isStudent);
        if (armBox           != null) armBox.setDisable(!isStudent);
        if (genderBox        != null) genderBox.setDisable(!isStudent);
        if (parentPhoneField != null) parentPhoneField.setDisable(false);
        if (subjectCheckBoxContainer != null)
            subjectCheckBoxContainer.setDisable(!isStaff);
    }

    @FXML
    private void suggestAdmissionNo() {
        String cls = classBox.getValue();
        if (cls == null || admissionField == null) return;
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT COUNT(*) FROM student_profiles " +
                 "WHERE class_level = ?")) {
            ps.setString(1, cls);
            ResultSet rs = ps.executeQuery();
            rs.next();
            int n = rs.getInt(1) + 1;
            admissionField.setText(
                "KLC/" + cls + "/" + String.format("%03d", n));
        } catch (Exception ignored) {}
    }

    @FXML
    private void choosePassport() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select Passport Photo");
        fc.getExtensionFilters().add(
            new FileChooser.ExtensionFilter(
                "Images","*.jpg","*.jpeg","*.png"));
        try {
            File f = fc.showOpenDialog(nameField.getScene().getWindow());
            if (f != null) setPassportFile(f);
        } catch (Exception e) {
            if (statusLabel != null)
                statusLabel.setText("File chooser error: " + e.getMessage());
        }
    }

    @FXML
    private void captureWebcam() {
        try {
            Class<?> webcamClass =
                Class.forName("com.github.sarxos.webcam.Webcam");
            Object webcam =
                webcamClass.getMethod("getDefault").invoke(null);
            if (webcam == null) {
                if (statusLabel != null)
                    statusLabel.setText(
                        "No webcam detected. Use Upload Photo instead.");
                return;
            }
            webcamClass.getMethod("open").invoke(webcam);
            java.awt.image.BufferedImage bi =
                (java.awt.image.BufferedImage)
                webcamClass.getMethod("getImage").invoke(webcam);
            webcamClass.getMethod("close").invoke(webcam);
            if (bi != null) {
                File dir = new File("klc_assets/passports");
                dir.mkdirs();
                File out = new File(dir,
                    "passport_tmp_" + System.currentTimeMillis() + ".jpg");
                javax.imageio.ImageIO.write(bi, "JPG", out);
                setPassportFile(out);
                if (statusLabel != null)
                    statusLabel.setText("Webcam photo captured successfully.");
            }
        } catch (ClassNotFoundException cnf) {
            if (statusLabel != null)
                statusLabel.setText("Webcam not available. Use Upload Photo.");
        } catch (Exception e) {
            if (statusLabel != null)
                statusLabel.setText("Webcam error: " + e.getMessage());
        }
    }

    private void setPassportFile(File f) {
        try {
            passportFilePath = f.getAbsolutePath();
            if (passportPreview != null)
                passportPreview.setImage(
                    new Image(f.toURI().toString(), 120, 140, true, true));
            if (passportPathLabel != null)
                passportPathLabel.setText(f.getName());
            if (statusLabel != null)
                statusLabel.setText("Photo selected: " + f.getName());
        } catch (Exception ignored) {}
    }

    @FXML
    private void handleRegister() throws Exception {
        String role = roleBox.getValue();

        if (passConfirmField != null
                && !passField.getText().equals(
                    passConfirmField.getText())) {
            setStatus("Passwords do not match", true);
            return;
        }
        if (passField.getText().length() < 6) {
            setStatus("Password must be at least 6 characters", true);
            return;
        }

        ArrayList<String> chosenSubjects = new ArrayList<>();
        if (!"STUDENT".equals(role) && !"SUPER_ADMIN".equals(role)
                && !"PARENT".equals(role)) {
            for (CheckBox cb : subjectChecks)
                if (cb.isSelected())
                    chosenSubjects.add((String) cb.getUserData());
            if (chosenSubjects.isEmpty()) {
                setStatus("Teachers must select at least ONE subject.", true);
                return;
            }
        }

        String secQ = securityQuestionBox == null
                    ? null : securityQuestionBox.getValue();
        String secA = securityAnswerField == null
                    ? null : securityAnswerField.getText();

        String result = AuthService.register(
            nameField.getText(),
            emailField.getText(),
            passField.getText(),
            role,
            codeField.getText(),
            admissionField != null ? admissionField.getText() : "",
            classBox.getValue() == null ? "" : classBox.getValue(),
            armBox.getValue()   == null ? "" : armBox.getValue(),
            surnameField != null ? surnameField.getText() : "",
            chosenSubjects.toArray(new String[0]),
            secQ, secA
        );

        if (result.startsWith("OK")) {
            String userId = result.substring(3);

            // Priority 2 #10: save phone metadata on users for ALL roles.
            // Null-safe + failure-safe: registration still succeeds if the
            // columns are not migrated yet.
            savePhoneMetadata(userId);

            // Save passport photo + student profile extras for students
            if ("STUDENT".equals(role) && passportFilePath != null) {
                try {
                    File src = new File(passportFilePath);
                    String ext = src.getName().contains(".")
                        ? src.getName().substring(
                            src.getName().lastIndexOf('.'))
                        : ".jpg";
                    File dest = new File("klc_assets/passports",
                        (admissionField != null
                            ? admissionField.getText().replace('/', '_')
                            : userId) + ext);
                    new File("klc_assets/passports").mkdirs();
                    Files.copy(src.toPath(), dest.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);

                    try (Connection c = DatabaseManager.getConnection();
                         PreparedStatement ps = c.prepareStatement(
                             "UPDATE student_profiles " +
                             "SET passport_url=?, gender=?, parent_phone=? " +
                             "WHERE user_id=?")) {
                        ps.setString(1, dest.getPath());
                        ps.setString(2,
                            genderBox == null || genderBox.getValue() == null
                            ? null : genderBox.getValue());
                        ps.setString(3,
                            parentPhoneField == null
                            ? null : parentPhoneField.getText());
                        AuthService.setUuid(ps, 4, userId, c);
                        ps.executeUpdate();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            String pin = (surnameField != null
                    && !surnameField.getText().isBlank()
                ? surnameField.getText().toUpperCase().replaceAll("\\s+","")
                : "") +
                (classBox.getValue() == null ? "" : classBox.getValue());

            setStatus(
                "Account created! You can now login.\n" +
                ("STUDENT".equals(role)
                    ? "Result PIN: " + pin +
                      (admissionField != null
                          ? " | Admission No: " + admissionField.getText()
                          : "") +
                      (passportFilePath != null ? " | Photo saved" : "")
                    : "PARENT".equals(role)
                        ? "Parent account linked to ward admission no: " +
                          (admissionField != null
                              ? admissionField.getText() : "") +
                          ". Login to view your ward's results."
                        : "Staff account created. Subjects: " +
                          chosenSubjects.size()), false);
        } else {
            setStatus(result, true);
        }
    }

    /**
     * Stores phone type + preferred contact method on the users row.
     * Requires users.phone_type / users.phone_contact
     * (migration 01_klc_migration_v1_0.sql). Fails silently if the
     * columns do not exist yet so registration is never blocked.
     */
    private void savePhoneMetadata(String userId) {
        String pType = phoneTypeBox == null || phoneTypeBox.getValue() == null
                     ? null : phoneTypeBox.getValue();
        String pMode = contactMethodBox == null
                     || contactMethodBox.getValue() == null
                     ? null : contactMethodBox.getValue();
        String phone = parentPhoneField == null
                     ? null : parentPhoneField.getText().trim();

        if (pType == null && pMode == null
                && (phone == null || phone.isBlank())) return;

        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE users SET phone=?, phone_type=?, phone_contact=? " +
                 "WHERE id=?")) {
            ps.setString(1, phone == null || phone.isBlank() ? null : phone);
            ps.setString(2, pType);
            ps.setString(3, pMode);
            AuthService.setUuid(ps, 4, userId, c);
            ps.executeUpdate();
        } catch (Exception e) {
            // Columns may not be migrated yet - do not block registration
            System.err.println(
                "[Register] phone metadata not saved: " + e.getMessage());
        }
    }

    private void setStatus(String m, boolean err) {
        if (statusLabel == null) return;
        statusLabel.setText(m);
        statusLabel.setStyle(err
            ? "-fx-text-fill:#c0392b; -fx-font-weight:bold;"
            : "-fx-text-fill:#0f7a3a; -fx-font-weight:bold;");
    }

    @FXML
    private void backLogin() throws Exception {
        MainApp.setRoot("login.fxml", null);
    }
}

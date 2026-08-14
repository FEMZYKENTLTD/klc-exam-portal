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

public class RegisterController {

    @FXML private ComboBox<String> roleBox, classBox, armBox,
                                   genderBox, securityQuestionBox,
                                   phoneTypeBox, contactMethodBox;
    @FXML private TextField nameField, emailField, codeField,
                            admissionField, surnameField,
                            parentPhoneField, securityAnswerField;
    @FXML private PasswordField passField, passConfirmField;
    @FXML private Label statusLabel, passStrengthLabel;
    @FXML private VBox subjectCheckBoxContainer;
    @FXML private ImageView passportPreview;
    @FXML private Label passportPathLabel;

    private final Map<String, String> subjectMap = new HashMap<>();
    private final ArrayList<CheckBox> subjectChecks = new ArrayList<>();
    private String passportFilePath = null;

    @FXML
    public void initialize() {
        roleBox.getItems().addAll(
            "STUDENT","TEACHER","EXAM_OFFICER",
            "PRINCIPAL_ADMIN","SUPER_ADMIN");
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

        if (phoneTypeBox != null) {
            phoneTypeBox.getItems().addAll("Personal", "Parent", "Guardian");
            phoneTypeBox.setValue("Parent");
        }
        if (contactMethodBox != null) {
            contactMethodBox.getItems().addAll("Call", "WhatsApp", "Both");
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

        if (p.length() < 6) { msg = "Too short"; }
        else if (p.length() < 8) { msg = "Weak"; }
        else if (p.matches(".*[A-Z].*") && p.matches(".*[0-9].*")
                && p.matches(".*[^A-Za-z0-9].*")) {
            msg = "Strong"; color = "#0f7a3a";
        } else if (p.matches(".*[A-Z].*") && p.matches(".*[0-9].*")) {
            msg = "Good"; color = "#d4a017";
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
                "SELECT MIN(id) AS id, subject_name " +
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
        if (admissionField != null) admissionField.setDisable(!isStudent);
    }

    private void suggestAdmissionNo() {
        if (admissionField == null) return;
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM student_profiles WHERE class_level=?")) {
            ps.setString(1, classBox.getValue());
            ResultSet rs = ps.executeQuery();
            rs.next();
            int next = rs.getInt(1) + 101;
            String cls = classBox.getValue().replaceAll("[^A-Za-z0-9]", "");
            admissionField.setText("KLC/" + cls + "/" + next);
        } catch (Exception ignored) {}
    }

    @FXML
    private void browsePassport() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select Passport Photograph");
        fc.getExtensionFilters().add(
            new FileChooser.ExtensionFilter(
                "Image Files", "*.jpg", "*.jpeg", "*.png"));
        File f = fc.showOpenDialog(
            nameField != null ? nameField.getScene().getWindow() : null);

        if (f != null) {
            passportFilePath = f.getAbsolutePath();
            if (passportPathLabel != null)
                passportPathLabel.setText(f.getName());
            if (passportPreview != null) {
                try {
                    passportPreview.setImage(
                        new Image(f.toURI().toString(), 100, 100, true, true));
                } catch (Exception ignored) {}
            }
        }
    }

    @FXML
    private void register() {
        String name  = nameField.getText()  == null ? "" : nameField.getText().trim();
        String email = emailField.getText() == null ? "" : emailField.getText().trim();
        String pass  = passField.getText()  == null ? "" : passField.getText();
        String conf  = passConfirmField != null && passConfirmField.getText() != null
                     ? passConfirmField.getText() : "";
        String role  = roleBox.getValue();
        String code  = codeField.getText()  == null ? "" : codeField.getText().trim();

        if (name.isBlank() || email.isBlank() || pass.isBlank()) {
            setStatus("Name, email and password are required.", true);
            return;
        }
        if (!pass.equals(conf) && !conf.isEmpty()) {
            setStatus("Passwords do not match.", true);
            return;
        }

        String phoneVal = parentPhoneField == null ? "" : parentPhoneField.getText().trim();
        String phoneType = phoneTypeBox == null || phoneTypeBox.getValue() == null
                         ? "Parent" : phoneTypeBox.getValue();
        String contactMode = contactMethodBox == null || contactMethodBox.getValue() == null
                           ? "Both" : contactMethodBox.getValue();
        String fullPhoneMeta = phoneVal.isEmpty() ? null
                             : (phoneVal + " [" + phoneType + "/" + contactMode + "]");

        ArrayList<String> chosenSubjects = new ArrayList<>();
        for (CheckBox cb : subjectChecks) {
            if (cb.isSelected()) {
                chosenSubjects.add((String) cb.getUserData());
            }
        }

        String secQ = securityQuestionBox == null || securityQuestionBox.getValue() == null
                    ? "What is your mother's maiden name?"
                    : securityQuestionBox.getValue();
        String secA = securityAnswerField == null ? ""
                    : securityAnswerField.getText().trim();

        String result = AuthService.register(
            name, email, pass, role, code,
            admissionField == null ? "" : admissionField.getText().trim(),
            classBox.getValue() == null ? "" : classBox.getValue(),
            armBox.getValue() == null ? "" : armBox.getValue(),
            surnameField != null ? surnameField.getText() : "",
            chosenSubjects.toArray(new String[0]),
            secQ, secA
        );

        if (result.startsWith("OK")) {
            String userId = result.substring(3);

            if ("STUDENT".equals(role) && passportFilePath != null) {
                try {
                    File src = new File(passportFilePath);
                    String ext = src.getName().contains(".")
                        ? src.getName().substring(src.getName().lastIndexOf('.'))
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
                        ps.setString(2, genderBox == null || genderBox.getValue() == null
                            ? null : genderBox.getValue());
                        ps.setString(3, fullPhoneMeta);
                        AuthService.setUuid(ps, 4, userId, c);
                        ps.executeUpdate();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            String pin = (surnameField != null && !surnameField.getText().isBlank()
                ? surnameField.getText().toUpperCase().replaceAll("\\s+","")
                : "") + (classBox.getValue() == null ? "" : classBox.getValue());

            setStatus("Account created! You can now login.\n" +
                ("STUDENT".equals(role)
                    ? "Result PIN: " + pin +
                      (admissionField != null ? " | Admission No: " + admissionField.getText() : "") +
                      (passportFilePath != null ? " | Photo saved" : "")
                    : "Staff account created. Subjects: " + chosenSubjects.size()), false);
        } else {
            setStatus(result, true);
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
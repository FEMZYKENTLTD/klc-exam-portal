package com.femzyk.klc.admin;

import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.db.DatabaseManager;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;

import java.io.File;
import java.sql.*;
import java.util.*;

public class QuestionEditorController {

    @FXML private ComboBox<String> subjectBox, classBox, diffBox, correctBox;

    // questionText and explanationText stay as TextArea (multi-line)
    @FXML private TextArea questionText, explanationText;

    // optA-E declared as TextArea to match FXML
    @FXML private TextArea optA, optB, optC, optD, optE;

    @FXML private TextField topicField, imagePathField;
    @FXML private Label status;

    private final Map<String, String> subjectMap = new HashMap<>();
    private String editingId = null;

    @FXML
    public void initialize() {
        classBox.getItems().addAll(
            "JSS1","JSS2","JSS3","SS1","SS2","SS3");
        diffBox.getItems().addAll("Easy","Medium","Hard");
        diffBox.setValue("Medium");
        correctBox.getItems().addAll("A","B","C","D","E");
        correctBox.setValue("A");
        loadSubjects();
    }

    void loadSubjects() {
        try (Connection c = DatabaseManager.getConnection()) {
            String sql = AuthService.isSuperAdmin()
                ? "SELECT id, subject_code FROM subjects " +
                  "WHERE is_active = TRUE ORDER BY subject_code"
                : "SELECT s.id, s.subject_code FROM subjects s " +
                  "JOIN teacher_subjects ts ON ts.subject_id = s.id " +
                  "WHERE ts.teacher_id = ? ORDER BY subject_code";

            PreparedStatement ps = c.prepareStatement(sql);
            if (!AuthService.isSuperAdmin())
                AuthService.setUuid(ps, 1, AuthService.Session.userId, c);

            ResultSet rs = ps.executeQuery();
            subjectBox.getItems().clear();
            subjectMap.clear();
            while (rs.next()) {
                subjectBox.getItems().add(rs.getString(2));
                subjectMap.put(rs.getString(2), rs.getString(1));
            }

            if (subjectBox.getItems().isEmpty()) {
                status.setText("No subjects available. " +
                    (AuthService.isSuperAdmin()
                        ? "Add subjects in Subject Manager."
                        : "Contact admin to assign subjects to you."));
            }
        } catch (Exception e) {
            if (status != null)
                status.setText("Error loading subjects: " + e.getMessage());
        }
    }

    @FXML
    private void chooseImage() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select Question Image or Diagram");
        fc.getExtensionFilters().add(
            new FileChooser.ExtensionFilter(
                "Images", "*.png","*.jpg","*.jpeg","*.gif","*.bmp"));
        File f = fc.showOpenDialog(questionText.getScene().getWindow());
        if (f != null) {
            imagePathField.setText(f.getAbsolutePath());
            setStatus("Image selected: " + f.getName() +
                " - stored as reference path. " +
                "Upload to Supabase Storage for cloud delivery.", false);
        }
    }

    @FXML
    private void saveQuestion() {
        if (questionText.getText().trim().isEmpty()) {
            setStatus("Please enter the question text.", true);
            return;
        }
        if (subjectBox.getValue() == null) {
            setStatus("Please select a subject.", true);
            return;
        }
        if (optA.getText().trim().isEmpty() || optB.getText().trim().isEmpty()) {
            setStatus("Please enter at least options A and B.", true);
            return;
        }

        try (Connection c = DatabaseManager.getConnection()) {
            String qid = (editingId != null)
                ? editingId
                : UUID.randomUUID().toString();

            String subjectId = subjectMap.get(subjectBox.getValue());

            if (editingId == null) {
                // INSERT new question
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO questions(" +
                        "  id, subject_id, class_level, topic, difficulty, " +
                        "  question_text, explanation, question_image_url, " +
                        "  created_by, is_approved) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,FALSE)")) {
                    AuthService.setUuid(ps, 1, qid, c);
                    AuthService.setUuid(ps, 2, subjectId, c);
                    ps.setString(3, classBox.getValue());
                    ps.setString(4, topicField.getText().trim());
                    ps.setString(5, diffBox.getValue());
                    ps.setString(6, questionText.getText().trim());
                    ps.setString(7, explanationText.getText().trim());
                    ps.setString(8, imagePathField.getText().isBlank()
                        ? null : imagePathField.getText());
                    AuthService.setUuid(ps, 9,
                        AuthService.Session.userId, c);
                    ps.executeUpdate();
                }
            } else {
                // UPDATE existing question
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE questions SET " +
                        "  question_text=?, explanation=?, topic=?, " +
                        "  difficulty=?, question_image_url=? " +
                        "WHERE id=?")) {
                    ps.setString(1, questionText.getText().trim());
                    ps.setString(2, explanationText.getText().trim());
                    ps.setString(3, topicField.getText().trim());
                    ps.setString(4, diffBox.getValue());
                    ps.setString(5, imagePathField.getText().isBlank()
                        ? null : imagePathField.getText());
                    AuthService.setUuid(ps, 6, qid, c);
                    ps.executeUpdate();
                }
                // Delete old options before re-inserting
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM question_options WHERE question_id=?")) {
                    AuthService.setUuid(ps, 1, qid, c);
                    ps.executeUpdate();
                }
            }

            // Insert options A-E
            TextArea[] optFields = {optA, optB, optC, optD, optE};
            String[]   labels    = {"A","B","C","D","E"};
            String     correct   = correctBox.getValue();
            int        inserted  = 0;

            for (int i = 0; i < optFields.length; i++) {
                String optText = optFields[i].getText().trim();
                if (optText.isEmpty()) continue;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO question_options(" +
                        "  question_id, option_label, option_text, is_correct) " +
                        "VALUES(?,?,?,?)")) {
                    AuthService.setUuid(ps, 1, qid, c);
                    ps.setString(2, labels[i]);
                    ps.setString(3, optText);
                    ps.setBoolean(4, labels[i].equals(correct));
                    ps.executeUpdate();
                    inserted++;
                }
            }

            AuthService.logAudit(
                editingId == null ? "QUESTION_CREATE" : "QUESTION_UPDATE",
                "questions", qid);

            setStatus(
                (editingId == null ? "Question created" : "Question updated") +
                " | Options: " + inserted +
                " | Correct: " + correct +
                " | Image: " + (imagePathField.getText().isBlank() ? "No" : "Yes") +
                " | Pending approval before appearing in exams.",
                false);

            editingId = null;
            clearForm();

        } catch (Exception e) {
            setStatus("Error: " + e.getMessage(), true);
            e.printStackTrace();
        }
    }

    void clearForm() {
        if (questionText    != null) questionText.clear();
        if (explanationText != null) explanationText.clear();
        if (optA != null) optA.clear();
        if (optB != null) optB.clear();
        if (optC != null) optC.clear();
        if (optD != null) optD.clear();
        if (optE != null) optE.clear();
        if (topicField     != null) topicField.clear();
        if (imagePathField != null) imagePathField.clear();
    }

    private void setStatus(String msg, boolean error) {
        if (status == null) return;
        status.setText(msg);
        status.setStyle(error
            ? "-fx-text-fill:#c0392b; -fx-font-size:12px;"
            : "-fx-text-fill:#0f7a3a; -fx-font-size:12px;");
    }
}
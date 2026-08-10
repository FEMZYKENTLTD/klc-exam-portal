package com.femzyk.klc.student;

import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.db.DatabaseManager;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.sql.*;
import java.util.*;

public class PracticeExamController {

    @FXML private VBox      practiceRoot;
    @FXML private Label     statusLabel;
    @FXML private ComboBox<String> subjectBox;
    @FXML private ComboBox<String> classBox;
    @FXML private Button    startPracticeBtn;

    private final Map<String, String> subjectMap    = new HashMap<>();
    private final Map<String, String> subjectIdMap  = new HashMap<>();

    @FXML
    public void initialize() {
        classBox.getItems().addAll(
            "JSS1","JSS2","JSS3","SS1","SS2","SS3");
        loadSubjects();
    }

    private void loadSubjects() {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT MIN(id) AS id, subject_name " +
                 "FROM subjects WHERE is_active = TRUE " +
                 "GROUP BY subject_name ORDER BY subject_name");
             ResultSet rs = ps.executeQuery()) {

            subjectBox.getItems().clear();
            subjectMap.clear();
            subjectIdMap.clear();

            while (rs.next()) {
                String id   = rs.getString("id");
                String name = rs.getString("subject_name");
                subjectBox.getItems().add(name);
                subjectMap.put(name, id);
                subjectIdMap.put(name, id);
            }

            if (subjectMap.isEmpty())
                setStatus("No active subjects found. Contact admin.", true);

        } catch (Exception e) {
            setStatus("Error loading subjects: " + e.getMessage(), true);
        }
    }

    @FXML
    private void startPractice() {
        String subject    = subjectBox.getValue();
        String classLevel = classBox.getValue();

        if (subject == null || subject.isBlank()) {
            setStatus("Please select a subject.", true);
            return;
        }
        if (classLevel == null || classLevel.isBlank()) {
            setStatus("Please select a class level.", true);
            return;
        }

        setStatus("Searching for practice questions...", false);
        if (startPracticeBtn != null) startPracticeBtn.setDisable(true);

        try {
            // Find a practice exam OR create a virtual practice session
            // from approved questions for this subject + class
            String practiceExamId = findOrCreatePracticeExam(
                subject, classLevel);

            if (practiceExamId == null) {
                setStatus("No approved questions found for " +
                    subject + " - " + classLevel +
                    ". Ask your teacher to upload and approve questions.", false);
                if (startPracticeBtn != null)
                    startPracticeBtn.setDisable(false);
                return;
            }

            // Open exam.fxml with practice exam ID
            // Practice mode: no malpractice detection, no result saved
            FXMLLoader loader = new FXMLLoader(
                getClass().getResource("/fxml/exam.fxml"));

            Stage currentStage = (Stage) (startPracticeBtn != null
                ? startPracticeBtn.getScene().getWindow()
                : statusLabel.getScene().getWindow());

            double w = currentStage.getWidth();
            double h = currentStage.getHeight();

            Scene scene = new Scene(loader.load(), w, h);
            scene.getStylesheets().add(
                getClass().getResource(
                    "/css/klc-premium.css").toExternalForm());

            ExamController ctrl = loader.getController();
            ctrl.startExam(practiceExamId, "A");  // practice variant A

            currentStage.setScene(scene);

        } catch (Exception e) {
            e.printStackTrace();
            setStatus("Error: " + e.getMessage(), true);
            if (startPracticeBtn != null)
                startPracticeBtn.setDisable(false);
        }
    }

    /**
     * Finds an existing practice exam for this subject+class,
     * OR creates a temporary one from approved questions.
     * Returns the exam ID or null if no questions available.
     */
    private String findOrCreatePracticeExam(
            String subject, String classLevel) throws Exception {

        try (Connection c = DatabaseManager.getConnection()) {

            // First: check for an existing practice exam
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT e.id FROM exams e " +
                    "JOIN subjects s ON s.id = e.subject_id " +
                    "WHERE s.subject_name = ? " +
                    "  AND e.class_level = ? " +
                    "  AND e.is_practice = TRUE " +
                    "  AND e.is_active = TRUE " +
                    "ORDER BY e.created_at DESC LIMIT 1")) {
                ps.setString(1, subject);
                ps.setString(2, classLevel);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) return rs.getString(1);
            }

            // Check if approved questions exist
            int questionCount;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM questions q " +
                    "JOIN subjects s ON s.id = q.subject_id " +
                    "WHERE s.subject_name = ? " +
                    "  AND q.class_level = ? " +
                    "  AND q.is_approved = TRUE")) {
                ps.setString(1, subject);
                ps.setString(2, classLevel);
                ResultSet rs = ps.executeQuery();
                rs.next();
                questionCount = rs.getInt(1);
            }

            if (questionCount == 0) return null;

            // Get subject ID
            String subjectId = subjectIdMap.get(subject);
            if (subjectId == null) return null;

            // Create temporary practice exam
            String examId = UUID.randomUUID().toString();
            String saId   = AuthService.Session.userId;

            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO exams(" +
                    "  id, subject_id, class_level, title, " +
                    "  duration_minutes, is_practice, is_active, " +
                    "  attempt_limit, negative_marking, created_by) " +
                    "VALUES(?,?,?,?,?,TRUE,TRUE,999,0,?)")) {
                AuthService.setUuid(ps, 1, examId, c);
                AuthService.setUuid(ps, 2, subjectId, c);
                ps.setString(3, classLevel);
                ps.setString(4,
                    "Practice: " + subject + " - " + classLevel);
                ps.setInt(5, 30); // 30 minutes for practice
                AuthService.setUuid(ps, 6, saId, c);
                ps.executeUpdate();
            }

            // Attach up to 20 random approved questions
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT q.id FROM questions q " +
                    "JOIN subjects s ON s.id = q.subject_id " +
                    "WHERE s.subject_name = ? " +
                    "  AND q.class_level = ? " +
                    "  AND q.is_approved = TRUE " +
                    "ORDER BY RANDOM() LIMIT 20")) {
                ps.setString(1, subject);
                ps.setString(2, classLevel);
                ResultSet rs = ps.executeQuery();
                int order = 1;
                while (rs.next()) {
                    String qid = rs.getString(1);
                    try (PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO exam_questions(" +
                            "exam_id, question_id, question_order) " +
                            "VALUES(?,?,?)")) {
                        AuthService.setUuid(ins, 1, examId, c);
                        AuthService.setUuid(ins, 2, qid, c);
                        ins.setInt(3, order++);
                        ins.executeUpdate();
                    } catch (Exception ignored) {}
                }
            }

            return examId;
        }
    }

    private void setStatus(String msg, boolean error) {
        if (statusLabel == null) return;
        statusLabel.setText(msg);
        statusLabel.setStyle(error
            ? "-fx-text-fill:#e74c3c; -fx-font-weight:bold;"
            : "-fx-text-fill:#2ecc71; -fx-font-weight:bold;");
    }

    // Legacy inner model kept for compatibility
    public static class PracticeQuestion {
        public String id, text, imageUrl, type, correctLabel;
        public Map<String, String> opts = new LinkedHashMap<>();
        @Override public String toString() { return text; }
    }
}
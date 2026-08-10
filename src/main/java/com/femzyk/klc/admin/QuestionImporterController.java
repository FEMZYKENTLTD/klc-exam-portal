package com.femzyk.klc.admin;

import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.db.DatabaseManager;
import com.femzyk.klc.parser.DocxQuestionParser;
import com.femzyk.klc.parser.PdfQuestionParser;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;

import java.io.*;
import java.sql.*;
import java.util.*;

public class QuestionImporterController {

    @FXML private ComboBox<String> subjectBox;
    @FXML private ComboBox<String> classBox;
    @FXML private Label            statusLabel;
    @FXML private TextArea         previewArea;   // Now EDITABLE
    @FXML private TextArea         answerKeyArea;

    private final Map<String, String> subjectMap = new HashMap<>();

    @FXML
    public void initialize() {
        classBox.getItems().addAll(
            "JSS1","JSS2","JSS3","SS1","SS2","SS3");
        loadSubjects();

        // Make preview editable so users can fix parsed questions
        if (previewArea != null) {
            previewArea.setEditable(true);
            previewArea.setPromptText(
                "Parsed questions appear here. You can EDIT them before importing.");
        }
    }

    private void loadSubjects() {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT MIN(id) AS id, subject_name " +
                 "FROM subjects WHERE is_active = TRUE " +
                 "GROUP BY subject_name ORDER BY subject_name")) {
            ResultSet rs = ps.executeQuery();
            subjectBox.getItems().clear();
            subjectMap.clear();
            while (rs.next()) {
                String name = rs.getString("subject_name");
                subjectBox.getItems().add(name);
                subjectMap.put(name, rs.getString("id"));
            }
        } catch (Exception e) {
            if (statusLabel != null)
                statusLabel.setText("Error loading subjects: " + e.getMessage());
        }
    }

    @FXML
    private void chooseFile() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select Question File");
        fc.getExtensionFilters().add(
            new FileChooser.ExtensionFilter(
                "Question Files", "*.pdf","*.docx","*.csv","*.txt"));
        File f = fc.showOpenDialog(
            previewArea != null ? previewArea.getScene().getWindow() : null);
        if (f == null) return;

        try {
            List<String> lines;
            String name = f.getName().toLowerCase();
            if (name.endsWith(".pdf"))
                lines = PdfQuestionParser.parse(f);
            else if (name.endsWith(".docx"))
                lines = DocxQuestionParser.parse(f);
            else
                lines = parseTextFile(f);

            // Show parsed questions in editable preview
            // Format: 1. Question text
            StringBuilder sb = new StringBuilder();
            int n = 1;
            for (String line : lines)
                sb.append(n++).append(". ").append(line).append("\n");

            if (previewArea != null) {
                previewArea.setText(sb.toString());
                previewArea.setEditable(true);
            }

            setStatus("Parsed " + lines.size() +
                " questions. You can edit them above before importing.", false);

        } catch (Exception e) {
            setStatus("Parse error: " + e.getMessage(), true);
        }
    }

    private List<String> parseTextFile(File file) throws Exception {
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null)
                if (!line.trim().isEmpty()) lines.add(line.trim());
        }
        return lines;
    }

    /**
     * Parse the (possibly edited) preview area into a clean list of questions.
     * Handles format: "1. Question text" or plain lines.
     */
    private List<String> getQuestionsFromPreview() {
        if (previewArea == null || previewArea.getText().isBlank())
            return Collections.emptyList();

        List<String> questions = new ArrayList<>();
        String[] lines = previewArea.getText().split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            // Remove leading "1. " "12. " numbering if present
            trimmed = trimmed.replaceFirst("^\\d+\\.\\s*", "").trim();
            if (!trimmed.isEmpty()) questions.add(trimmed);
        }
        return questions;
    }

    @FXML
    private void commitImport() {
        // Read from the (possibly edited) preview area
        List<String> questionsToImport = getQuestionsFromPreview();

        if (questionsToImport.isEmpty()) {
            setStatus("No questions in preview. Choose a file first.", true);
            return;
        }

        String subjectName = subjectBox.getValue();
        if (subjectName == null) {
            setStatus("Please select a Subject.", true);
            return;
        }
        if (classBox.getValue() == null) {
            setStatus("Please select a Class Level.", true);
            return;
        }

        // Parse answer key
        Map<Integer, String> answers = new HashMap<>();
        if (answerKeyArea != null) {
            String ak = answerKeyArea.getText().toUpperCase();
            java.util.regex.Matcher m =
                java.util.regex.Pattern
                    .compile("(\\d+)\\s*[:.,-]\\s*([A-E])")
                    .matcher(ak);
            while (m.find())
                answers.put(Integer.parseInt(m.group(1)), m.group(2));
        }

        if (!answers.isEmpty() && answers.size() != questionsToImport.size()) {
            setStatus("Answer Key MISMATCH: " + answers.size() +
                " answers for " + questionsToImport.size() +
                " questions. Either fix the preview or the answer key.", true);
            return;
        }

        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            String subjectId = subjectMap.get(subjectName);
            int imported = 0;

            for (int i = 0; i < questionsToImport.size(); i++) {
                String questionText = questionsToImport.get(i);
                String qid          = UUID.randomUUID().toString();
                String correct      = answers.isEmpty()
                    ? "A" : answers.getOrDefault(i + 1, "A");

                // Insert question
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO questions(" +
                        "  id, subject_id, class_level, " +
                        "  question_text, created_by, is_approved) " +
                        "VALUES(?,?,?,?,?,FALSE)")) {
                    AuthService.setUuid(ps, 1, qid, c);
                    AuthService.setUuid(ps, 2, subjectId, c);
                    ps.setString(3, classBox.getValue());
                    ps.setString(4, questionText);
                    AuthService.setUuid(ps, 5,
                        AuthService.Session.userId, c);
                    ps.executeUpdate();
                }

                // Insert default options A-D
                for (String opt : new String[]{"A","B","C","D"}) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO question_options(" +
                            "  question_id, option_label, " +
                            "  option_text, is_correct) " +
                            "VALUES(?,?,?,?)")) {
                        AuthService.setUuid(ps, 1, qid, c);
                        ps.setString(2, opt);
                        ps.setString(3, "Option " + opt +
                            " (edit in Question Editor)");
                        ps.setBoolean(4, opt.equals(correct));
                        ps.executeUpdate();
                    }
                }
                imported++;
            }

            c.commit();
            AuthService.logAudit("QUESTION_IMPORT", "questions",
                subjectId);
            setStatus("Successfully imported " + imported +
                " questions. They are PENDING approval." +
                (answers.isEmpty()
                    ? " No answer key - please edit options in Question Editor."
                    : ""), false);

            // Clear preview and answer key
            if (previewArea   != null) previewArea.clear();
            if (answerKeyArea != null) answerKeyArea.clear();

        } catch (Exception e) {
            setStatus("DB Error: " + e.getMessage(), true);
            e.printStackTrace();
        }
    }

    private void setStatus(String msg, boolean error) {
        if (statusLabel == null) return;
        statusLabel.setText(msg);
        statusLabel.setStyle(error
            ? "-fx-text-fill:#ef4444; -fx-font-weight:bold;"
            : "-fx-text-fill:#0f7a3a; -fx-font-weight:bold;");
    }
}
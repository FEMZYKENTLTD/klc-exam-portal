package com.femzyk.klc.admin;

import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.db.DatabaseManager;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;

import java.io.File;
import java.sql.*;
import java.util.*;

/**
 * QuestionEditorController v1.0 - BULK SUBJECT EDITOR (Issue #5)
 *
 * REDESIGN as requested:
 *   1. Pick a Subject + Class -> Load Questions
 *   2. ALL questions for that subject/class appear in the left list
 *   3. Click any question -> edit text, options A-E, correct answer,
 *      topic, difficulty, explanation, image in the right panel
 *   4. Edits are kept in memory as you move between questions
 *   5. SAVE ALL CHANGES writes every modified question in ONE
 *      transaction
 *   6. "New Question" still creates a fresh question for the selected
 *      subject/class (feature preserved - nothing removed)
 *
 * RULES HONOURED:
 *   - AuthService.setUuid for every UUID bind (Rule 2)
 *   - Teachers see only their assigned subjects (permission matrix)
 *   - Subject ComboBox is EDITABLE with case-insensitive auto-matching
 *     against the database list (Issue #6)
 *   - All FXML fx:id / handler names match question_editor.fxml v1.0
 */
public class QuestionEditorController {

    // ── Pickers ──
    @FXML private ComboBox<String> subjectBox, classBox;
    @FXML private Button loadBtn;

    // ── Question list (left) ──
    @FXML private ListView<QDraft> questionList;
    @FXML private Label listInfoLabel;

    // ── Editor panel (right) ──
    @FXML private ComboBox<String> diffBox, correctBox;
    @FXML private TextArea questionText, explanationText;
    @FXML private TextArea optA, optB, optC, optD, optE;
    @FXML private TextField topicField, imagePathField;
    // KLC v1.0 spec 4.3: WAEC/NECO year + Bloom's taxonomy metadata
    @FXML private TextField yearField;
    @FXML private ComboBox<String> bloomBox;
    @FXML private Label status;

    private final Map<String, String> subjectMap = new HashMap<>();
    private final ObservableList<QDraft> drafts =
        FXCollections.observableArrayList();

    private QDraft current = null;

    /** In-memory editable copy of one question. */
    public static class QDraft {
        String id;                 // null = new question not yet saved
        String text = "", topic = "", difficulty = "Medium",
               explanation = "", imageUrl = "", correct = "A";
        String year = "", bloom = "", audioUrl = "";
        Map<String, String> opts = new LinkedHashMap<>();
        boolean dirty = false;
        boolean isNew = false;

        @Override public String toString() {
            String t = text == null ? "" : text.trim();
            String prefix = dirty ? "* " : (isNew ? "+ " : "");
            return prefix + (t.length() > 60 ? t.substring(0, 60) + "..." : t);
        }
    }

    @FXML
    public void initialize() {
        classBox.getItems().addAll(
            "JSS1","JSS2","JSS3","SS1","SS2","SS3");
        diffBox.getItems().addAll("Easy","Medium","Hard");
        // KLC v1.0 spec 4.3: Bloom's taxonomy
        if (bloomBox != null) bloomBox.getItems().addAll(
            "Remembering","Understanding","Applying","Analysing",
            "Evaluating","Creating");
        diffBox.setValue("Medium");
        correctBox.getItems().addAll("A","B","C","D","E");
        correctBox.setValue("A");

        // Issue #6: editable subject box with auto-matching
        subjectBox.setEditable(true);
        loadSubjects();
        subjectBox.getEditor().textProperty().addListener((o, ov, nv) -> {
            if (nv == null || nv.isBlank()) return;
            for (String s : subjectMap.keySet()) {
                if (s.equalsIgnoreCase(nv.trim())) {
                    if (!s.equals(subjectBox.getValue()))
                        subjectBox.setValue(s);
                    return;
                }
            }
        });

        questionList.setItems(drafts);
        questionList.getSelectionModel().selectedItemProperty()
            .addListener((o, ov, nv) -> {
                if (ov != null) captureEditor(ov);
                if (nv != null) showInEditor(nv);
            });

        setEditorEnabled(false);
    }

    private void loadSubjects() {
        try (Connection c = DatabaseManager.getConnection()) {
            String sql = AuthService.isSuperAdmin()
                || "PRINCIPAL_ADMIN".equals(AuthService.Session.role)
                ? "SELECT MIN(CAST(id AS VARCHAR(36))) AS id, subject_name FROM subjects " +
                  "WHERE is_active = TRUE " +
                  "GROUP BY subject_name ORDER BY subject_name"
                : "SELECT MIN(CAST(s.id AS VARCHAR(36))) AS id, s.subject_name FROM subjects s " +
                  "JOIN teacher_subjects ts ON ts.subject_id = s.id " +
                  "WHERE ts.teacher_id = ? AND s.is_active = TRUE " +
                  "GROUP BY s.subject_name ORDER BY s.subject_name";

            PreparedStatement ps = c.prepareStatement(sql);
            if (!(AuthService.isSuperAdmin()
                    || "PRINCIPAL_ADMIN".equals(AuthService.Session.role)))
                AuthService.setUuid(ps, 1, AuthService.Session.userId, c);

            ResultSet rs = ps.executeQuery();
            subjectBox.getItems().clear();
            subjectMap.clear();
            while (rs.next()) {
                subjectBox.getItems().add(rs.getString(2));
                subjectMap.put(rs.getString(2), rs.getString(1));
            }

            if (subjectBox.getItems().isEmpty()) {
                setStatus("No subjects available. " +
                    (AuthService.isSuperAdmin()
                        ? "Add subjects in Subject Manager."
                        : "Contact admin to assign subjects to you."), true);
            }
        } catch (Exception e) {
            setStatus("Error loading subjects: " + e.getMessage(), true);
        }
    }

    /** Resolve typed/selected subject name to its id (case-insensitive). */
    private String resolveSubjectId() {
        String typed = subjectBox.getEditor() != null
            ? subjectBox.getEditor().getText() : subjectBox.getValue();
        if (typed == null) typed = subjectBox.getValue();
        if (typed == null) return null;
        String t = typed.trim();
        for (Map.Entry<String, String> e : subjectMap.entrySet()) {
            if (e.getKey().equalsIgnoreCase(t)) {
                subjectBox.setValue(e.getKey());
                return e.getValue();
            }
        }
        return null;
    }

    // =====================================================================
    //  LOAD ALL QUESTIONS FOR SUBJECT + CLASS
    // =====================================================================
    @FXML
    private void loadQuestions() {
        String subjectId = resolveSubjectId();
        String cls = classBox.getValue();

        if (subjectId == null) {
            setStatus("Select or type a valid subject (it must exist in the database).", true);
            return;
        }
        if (cls == null) {
            setStatus("Select a class level.", true);
            return;
        }

        drafts.clear();
        current = null;
        clearEditor();
        setEditorEnabled(false);

        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT q.id, q.question_text, q.topic, q.difficulty, " +
                 "       q.explanation, q.question_image_url, " +
                 "       o.option_label, o.option_text, o.is_correct, " +
                 "       q.exam_year, q.bloom, q.question_audio_url " +
                 "FROM questions q " +
                 "JOIN subjects s ON s.id = q.subject_id " +
                 "LEFT JOIN question_options o ON o.question_id = q.id " +
                 "WHERE s.subject_name = (SELECT subject_name FROM subjects WHERE id = ?) " +
                 "  AND q.class_level = ? " +
                 "ORDER BY q.created_at, o.option_label")) {

            AuthService.setUuid(ps, 1, subjectId, c);
            ps.setString(2, cls);

            ResultSet rs = ps.executeQuery();
            Map<String, QDraft> map = new LinkedHashMap<>();
            while (rs.next()) {
                String qid = rs.getString(1);
                QDraft d = map.computeIfAbsent(qid, k -> {
                    QDraft n = new QDraft();
                    n.id = k;
                    try {
                        n.text        = rs.getString(2) == null ? "" : rs.getString(2);
                        n.topic       = rs.getString(3) == null ? "" : rs.getString(3);
                        n.difficulty  = rs.getString(4) == null ? "Medium" : rs.getString(4);
                        n.explanation = rs.getString(5) == null ? "" : rs.getString(5);
                        n.imageUrl    = rs.getString(6) == null ? "" : rs.getString(6);
                        n.year        = rs.getString(10) == null ? "" : rs.getString(10);
                        n.bloom       = rs.getString(11) == null ? "" : rs.getString(11);
                        n.audioUrl    = rs.getString(12) == null ? "" : rs.getString(12);
                    } catch (SQLException ignored) {}
                    return n;
                });
                String lbl = rs.getString(7);
                if (lbl != null) {
                    d.opts.put(lbl, rs.getString(8));
                    if (rs.getBoolean(9)) d.correct = lbl;
                }
            }
            drafts.addAll(map.values());

            if (drafts.isEmpty()) {
                listInfoLabel.setText("No questions yet for this subject/class. " +
                    "Click New Question to create the first one.");
                setStatus("No questions found. Use New Question to add.", false);
            } else {
                listInfoLabel.setText(drafts.size() +
                    " questions loaded. Click one to edit. " +
                    "* = unsaved changes.");
                setStatus("Loaded " + drafts.size() +
                    " questions. Edit freely, then SAVE ALL CHANGES.", false);
                questionList.getSelectionModel().selectFirst();
            }

        } catch (Exception e) {
            setStatus("Error loading questions: " + e.getMessage(), true);
            e.printStackTrace();
        }
    }

    // =====================================================================
    //  EDITOR <-> DRAFT
    // =====================================================================
    private void showInEditor(QDraft d) {
        current = d;
        setEditorEnabled(true);
        questionText.setText(d.text);
        topicField.setText(d.topic);
        diffBox.setValue(d.difficulty == null ? "Medium" : d.difficulty);
        if (yearField != null) yearField.setText(d.year);
        if (bloomBox  != null) bloomBox.setValue(
            d.bloom == null || d.bloom.isBlank() ? null : d.bloom);
        explanationText.setText(d.explanation);
        imagePathField.setText(d.imageUrl);
        correctBox.setValue(d.correct == null ? "A" : d.correct);
        optA.setText(d.opts.getOrDefault("A", ""));
        optB.setText(d.opts.getOrDefault("B", ""));
        optC.setText(d.opts.getOrDefault("C", ""));
        optD.setText(d.opts.getOrDefault("D", ""));
        optE.setText(d.opts.getOrDefault("E", ""));
    }

    /** Pull the editor fields back into the draft; mark dirty if changed. */
    private void captureEditor(QDraft d) {
        if (d == null) return;
        String newText = questionText.getText() == null ? "" : questionText.getText();
        String newTopic = topicField.getText() == null ? "" : topicField.getText();
        String newDiff = diffBox.getValue() == null ? "Medium" : diffBox.getValue();
        String newExp = explanationText.getText() == null ? "" : explanationText.getText();
        String newImg = imagePathField.getText() == null ? "" : imagePathField.getText();
        String newCor = correctBox.getValue() == null ? "A" : correctBox.getValue();
        String newYear = yearField == null || yearField.getText() == null
            ? "" : yearField.getText().trim();
        String newBloom = bloomBox == null || bloomBox.getValue() == null
            ? "" : bloomBox.getValue();

        Map<String, String> newOpts = new LinkedHashMap<>();
        putIfNotBlank(newOpts, "A", optA.getText());
        putIfNotBlank(newOpts, "B", optB.getText());
        putIfNotBlank(newOpts, "C", optC.getText());
        putIfNotBlank(newOpts, "D", optD.getText());
        putIfNotBlank(newOpts, "E", optE.getText());

        boolean changed =
            !newText.equals(d.text) || !newTopic.equals(d.topic)
            || !newDiff.equals(d.difficulty) || !newExp.equals(d.explanation)
            || !newImg.equals(d.imageUrl) || !newCor.equals(d.correct)
            || !newYear.equals(d.year) || !newBloom.equals(d.bloom)
            || !newOpts.equals(d.opts);

        if (changed) {
            d.text = newText; d.topic = newTopic; d.difficulty = newDiff;
            d.explanation = newExp; d.imageUrl = newImg; d.correct = newCor;
            d.year = newYear; d.bloom = newBloom;
            d.opts = newOpts;
            d.dirty = true;
            questionList.refresh();
        }
    }

    private void putIfNotBlank(Map<String, String> m, String k, String v) {
        if (v != null && !v.trim().isEmpty()) m.put(k, v.trim());
    }

    /** KLC v1.0: tolerant year parse - blank/invalid stores 0 (unknown). */
    private static int parseYear(String y) {
        try {
            return y == null || y.isBlank() ? 0 : Integer.parseInt(y.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // =====================================================================
    //  NEW QUESTION (feature preserved)
    // =====================================================================
    @FXML
    private void newQuestion() {
        if (resolveSubjectId() == null || classBox.getValue() == null) {
            setStatus("Select subject and class first, then New Question.", true);
            return;
        }
        captureEditor(current);
        QDraft d = new QDraft();
        d.isNew = true;
        d.dirty = true;
        drafts.add(d);
        questionList.getSelectionModel().select(d);
        questionText.requestFocus();
        setStatus("New question added to the list - fill it in, then SAVE ALL CHANGES.", false);
    }

    // =====================================================================
    //  SAVE ALL CHANGES - one transaction
    // =====================================================================
    @FXML
    private void saveAll() {
        captureEditor(current);

        String subjectId = resolveSubjectId();
        String cls = classBox.getValue();
        if (subjectId == null || cls == null) {
            setStatus("Select subject and class first.", true);
            return;
        }

        List<QDraft> toSave = new ArrayList<>();
        for (QDraft d : drafts) if (d.dirty) toSave.add(d);

        if (toSave.isEmpty()) {
            setStatus("No changes to save.", false);
            return;
        }

        // Validate
        for (QDraft d : toSave) {
            if (d.text.trim().isEmpty()) {
                setStatus("A question has empty text - fix it before saving.", true);
                questionList.getSelectionModel().select(d);
                return;
            }
            if (!d.opts.containsKey("A") || !d.opts.containsKey("B")) {
                setStatus("Every question needs at least options A and B.", true);
                questionList.getSelectionModel().select(d);
                return;
            }
            if (!d.opts.containsKey(d.correct)) {
                setStatus("A question's correct answer (" + d.correct +
                    ") has no option text.", true);
                questionList.getSelectionModel().select(d);
                return;
            }
        }

        int savedNew = 0, savedUpd = 0;
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try {
                for (QDraft d : toSave) {
                    if (d.isNew || d.id == null) {
                        d.id = UUID.randomUUID().toString();
                        try (PreparedStatement ps = c.prepareStatement(
                                "INSERT INTO questions(" +
                                "  id, subject_id, class_level, topic, difficulty, " +
                                "  question_text, explanation, question_image_url, " +
                                "  exam_year, bloom, created_by, is_approved) " +
                                "VALUES(?,?,?,?,?,?,?,?,?,?,?,FALSE)")) {
                            AuthService.setUuid(ps, 1, d.id, c);
                            AuthService.setUuid(ps, 2, subjectId, c);
                            ps.setString(3, cls);
                            ps.setString(4, d.topic);
                            ps.setString(5, d.difficulty);
                            ps.setString(6, d.text.trim());
                            ps.setString(7, d.explanation);
                            ps.setString(8, d.imageUrl.isBlank() ? null : d.imageUrl);
                            ps.setInt   (9, parseYear(d.year));
                            ps.setString(10, d.bloom.isBlank() ? null : d.bloom);
                            AuthService.setUuid(ps, 11,
                                AuthService.Session.userId, c);
                            ps.executeUpdate();
                        }
                        savedNew++;
                    } else {
                        try (PreparedStatement ps = c.prepareStatement(
                                "UPDATE questions SET " +
                                "  question_text=?, explanation=?, topic=?, " +
                                "  difficulty=?, question_image_url=?, " +
                                "  exam_year=?, bloom=? " +
                                "WHERE id=?")) {
                            ps.setString(1, d.text.trim());
                            ps.setString(2, d.explanation);
                            ps.setString(3, d.topic);
                            ps.setString(4, d.difficulty);
                            ps.setString(5, d.imageUrl.isBlank() ? null : d.imageUrl);
                            ps.setInt   (6, parseYear(d.year));
                            ps.setString(7, d.bloom.isBlank() ? null : d.bloom);
                            AuthService.setUuid(ps, 8, d.id, c);
                            ps.executeUpdate();
                        }
                        try (PreparedStatement ps = c.prepareStatement(
                                "DELETE FROM question_options WHERE question_id=?")) {
                            AuthService.setUuid(ps, 1, d.id, c);
                            ps.executeUpdate();
                        }
                        savedUpd++;
                    }

                    // (Re)insert options
                    for (Map.Entry<String, String> opt : d.opts.entrySet()) {
                        try (PreparedStatement ps = c.prepareStatement(
                                "INSERT INTO question_options(" +
                                "  question_id, option_label, option_text, is_correct) " +
                                "VALUES(?,?,?,?)")) {
                            AuthService.setUuid(ps, 1, d.id, c);
                            ps.setString(2, opt.getKey());
                            ps.setString(3, opt.getValue());
                            ps.setBoolean(4, opt.getKey().equals(d.correct));
                            ps.executeUpdate();
                        }
                    }
                }
                c.commit();
            } catch (Exception inner) {
                c.rollback();
                throw inner;
            } finally {
                c.setAutoCommit(true);
            }

            for (QDraft d : toSave) { d.dirty = false; d.isNew = false; }
            questionList.refresh();

            AuthService.logAudit("QUESTION_BULK_SAVE", "questions",
                subjectId);
            setStatus("SAVED: " + savedNew + " new, " + savedUpd +
                " updated. New questions are PENDING approval.", false);

        } catch (Exception e) {
            setStatus("Save failed - nothing was changed: " + e.getMessage(), true);
            e.printStackTrace();
        }
    }

    // =====================================================================
    //  IMAGE PICKER (feature preserved)
    // =====================================================================
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
            setStatus("Image selected: " + f.getName(), false);
        }
    }

    // =====================================================================
    //  HELPERS
    // =====================================================================
    private void setEditorEnabled(boolean on) {
        Control[] cs = { questionText, explanationText, optA, optB, optC,
                         optD, optE, topicField, imagePathField,
                         diffBox, correctBox };
        for (Control ctl : cs) if (ctl != null) ctl.setDisable(!on);
    }

    private void clearEditor() {
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
            ? "-fx-text-fill:#c0392b; -fx-font-size:12px; -fx-font-weight:bold;"
            : "-fx-text-fill:#0f7a3a; -fx-font-size:12px; -fx-font-weight:bold;");
    }
}

package com.femzyk.klc.admin;

import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.db.DatabaseManager;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.sql.*;
import java.util.*;

/**
 * QuestionBankController - KLC CBT Suite v1.0 (A2 FEATURE ADDED)
 *
 * NEW (A2): clicking a question in the table shows the FULL question
 * READ-ONLY in the detail panel below: text, options A-E with the
 * correct answer marked [CORRECT], difficulty, topic and explanation.
 * Editing stays in the Question Editor (by design).
 *
 * All v1.0 fixes preserved: no sockets/keys (Rule 3), setUuid (Rule 2),
 * role enforcement, audit logging, subject tree, search, class filter.
 */
public class QuestionBankController {

    @FXML private TreeView<String>  subjectTree;

    @FXML private TableView<QuestionRow>           questionTable;
    @FXML private TableColumn<QuestionRow, String> colQuestion, colClass,
                                                   colDifficulty, colApproved;

    @FXML private TextField        searchField;
    @FXML private ComboBox<String> classFilter;
    @FXML private Label            statusLabel;

    // A2: read-only detail panel
    @FXML private TextArea detailArea;

    private final ObservableList<QuestionRow> data    =
            FXCollections.observableArrayList();
    private final ObservableList<QuestionRow> allData =
            FXCollections.observableArrayList();

    private final Map<String, List<QuestionRow>> subjectQuestionMap =
            new LinkedHashMap<>();

    private String selectedSubject = null;

    public static class QuestionRow {
        String id, text, subject, classLevel, difficulty, approved;

        QuestionRow(String id, String t, String s, String c, String d, boolean app) {
            this.id = id; text = t; subject = s;
            classLevel = c; difficulty = d;
            approved = app ? "Approved" : "Pending";
        }

        public String getText()        { return text; }
        public String getSubject()     { return subject; }
        public String getClassLevel()  { return classLevel; }
        public String getDifficulty()  { return difficulty == null ? "-" : difficulty; }
        public String getApproved()    { return approved; }
    }

    private boolean canApprove() {
        String r = AuthService.Session.role;
        return "SUPER_ADMIN".equals(r) || "PRINCIPAL_ADMIN".equals(r)
            || "EXAM_OFFICER".equals(r);
    }

    private boolean canDelete() {
        String r = AuthService.Session.role;
        return "SUPER_ADMIN".equals(r) || "PRINCIPAL_ADMIN".equals(r);
    }

    private boolean isTeacher() {
        return "TEACHER".equals(AuthService.Session.role);
    }

    @FXML
    public void initialize() {
        colQuestion.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getText()));
        colClass.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getClassLevel()));
        colDifficulty.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getDifficulty()));
        if (colApproved != null)
            colApproved.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(
                    c.getValue().getApproved()));

        classFilter.getItems().addAll(
            "All","JSS1","JSS2","JSS3","SS1","SS2","SS3");
        classFilter.setValue("All");

        questionTable.setItems(data);

        // A2: show full question read-only when a row is selected
        questionTable.getSelectionModel().selectedItemProperty()
            .addListener((obs, ov, nv) -> {
                if (nv != null) showQuestionDetail(nv);
                else if (detailArea != null) detailArea.clear();
            });

        if (subjectTree != null) {
            subjectTree.getSelectionModel()
                .selectedItemProperty().addListener((obs, ov, nv) -> {
                if (nv != null && nv.isLeaf()
                        && nv.getParent() != null
                        && nv.getParent().getParent() != null) {
                    selectedSubject = nv.getParent().getValue()
                        .split(" \\(")[0];
                    showQuestionsForSubjectAndClass(
                        selectedSubject, nv.getValue().split(" ")[0]);
                } else if (nv != null && !nv.isLeaf()
                        && nv.getParent() != null) {
                    selectedSubject = nv.getValue().split(" \\(")[0];
                    showQuestionsForSubject(selectedSubject);
                }
            });
        }

        searchField.textProperty().addListener(
            (obs, ov, nv) -> filterQuestions());
        classFilter.valueProperty().addListener(
            (obs, ov, nv) -> filterQuestions());

        refreshQuestions();
    }

    // =====================================================================
    //  A2: READ-ONLY QUESTION DETAIL
    // =====================================================================
    private void showQuestionDetail(QuestionRow row) {
        if (detailArea == null) return;
        StringBuilder sb = new StringBuilder();
        try (Connection c = DatabaseManager.getConnection()) {

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT question_text, topic, difficulty, explanation " +
                    "FROM questions WHERE id = ?")) {
                AuthService.setUuid(ps, 1, row.id, c);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    sb.append("QUESTION  [").append(row.subject)
                      .append(" | ").append(row.classLevel == null ? "-" : row.classLevel)
                      .append(" | ").append(rs.getString(3) == null ? "-" : rs.getString(3))
                      .append(" | ").append(row.approved).append("]\n");
                    String topic = rs.getString(2);
                    if (topic != null && !topic.isBlank())
                        sb.append("Topic: ").append(topic).append("\n");
                    sb.append("\n").append(rs.getString(1)).append("\n\n");
                    String exp = rs.getString(4);
                    if (exp != null && !exp.isBlank()) {
                        sb.append("EXPLANATION:\n").append(exp).append("\n\n");
                    }
                }
            }

            sb.append("OPTIONS:\n");
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT option_label, option_text, is_correct " +
                    "FROM question_options WHERE question_id = ? " +
                    "ORDER BY option_label")) {
                AuthService.setUuid(ps, 1, row.id, c);
                ResultSet rs = ps.executeQuery();
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    sb.append("  ").append(rs.getString(1)).append(".  ")
                      .append(rs.getString(2));
                    if (rs.getBoolean(3)) sb.append("   [CORRECT]");
                    sb.append("\n");
                }
                if (!any) sb.append("  (no options recorded)\n");
            }

            sb.append("\nTo edit this question, open the Question Editor.");
            detailArea.setText(sb.toString());

        } catch (Exception e) {
            detailArea.setText("Could not load question detail: " + e.getMessage());
        }
    }

    // =====================================================================
    //  LOAD ALL QUESTIONS AND BUILD TREE
    // =====================================================================
    @FXML
    public void refreshQuestions() {
        data.clear();
        allData.clear();
        subjectQuestionMap.clear();
        if (detailArea != null) detailArea.clear();

        String sql = isTeacher()
            ? "SELECT q.id, q.question_text, s.subject_name, " +
              "q.class_level, q.difficulty, q.is_approved " +
              "FROM questions q " +
              "JOIN subjects s ON s.id = q.subject_id " +
              "JOIN teacher_subjects ts ON ts.subject_id = s.id " +
              "WHERE ts.teacher_id = ? " +
              "ORDER BY s.subject_name, q.class_level, q.created_at DESC"
            : "SELECT q.id, q.question_text, s.subject_name, " +
              "q.class_level, q.difficulty, q.is_approved " +
              "FROM questions q " +
              "JOIN subjects s ON s.id = q.subject_id " +
              "ORDER BY s.subject_name, q.class_level, q.created_at DESC";

        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {

            if (isTeacher())
                AuthService.setUuid(ps, 1, AuthService.Session.userId, c);

            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                QuestionRow row = new QuestionRow(
                    rs.getString(1), rs.getString(2),
                    rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getBoolean(6));
                allData.add(row);
                subjectQuestionMap
                    .computeIfAbsent(row.subject, k -> new ArrayList<>())
                    .add(row);
            }

            buildSubjectTree();
            setStatus("Loaded " + allData.size() +
                " questions across " + subjectQuestionMap.size() +
                " subjects. Click a subject, then a question to view it.", false);

        } catch (Exception e) {
            setStatus("Error: " + e.getMessage(), true);
        }
    }

    private void buildSubjectTree() {
        if (subjectTree == null) return;

        TreeItem<String> root = new TreeItem<>("All Subjects");
        root.setExpanded(true);

        for (Map.Entry<String, List<QuestionRow>> entry :
                subjectQuestionMap.entrySet()) {

            String subject = entry.getKey();
            List<QuestionRow> rows = entry.getValue();

            Map<String, Long> classCount = new LinkedHashMap<>();
            for (QuestionRow r : rows) {
                classCount.merge(
                    r.classLevel == null ? "General" : r.classLevel,
                    1L, Long::sum);
            }

            long total = rows.size();
            long approved = rows.stream()
                .filter(r -> "Approved".equals(r.approved)).count();
            long pending = total - approved;

            TreeItem<String> subjectItem = new TreeItem<>(
                subject + " (" + approved + " approved, " +
                pending + " pending)");

            for (Map.Entry<String, Long> cls : classCount.entrySet()) {
                TreeItem<String> clsItem = new TreeItem<>(
                    cls.getKey() + " - " + cls.getValue() + " questions");
                subjectItem.getChildren().add(clsItem);
            }

            root.getChildren().add(subjectItem);
        }

        subjectTree.setRoot(root);
    }

    private void showQuestionsForSubject(String subject) {
        data.clear();
        List<QuestionRow> rows = subjectQuestionMap.getOrDefault(
            subject, Collections.emptyList());
        data.addAll(rows);
        applyClassFilter();
        questionTable.setItems(data);
        setStatus("Showing " + data.size() + " questions for " + subject, false);
    }

    private void showQuestionsForSubjectAndClass(
            String subject, String classLevel) {
        data.clear();
        List<QuestionRow> rows = subjectQuestionMap.getOrDefault(
            subject, Collections.emptyList());
        for (QuestionRow r : rows) {
            if (classLevel.equals(r.classLevel)) data.add(r);
        }
        questionTable.setItems(data);
        setStatus("Showing " + data.size() + " questions for " +
            subject + " - " + classLevel, false);
    }

    private void applyClassFilter() {
        String cls = classFilter.getValue();
        if (cls == null || "All".equals(cls)) return;
        ObservableList<QuestionRow> filtered =
            FXCollections.observableArrayList();
        for (QuestionRow r : data)
            if (cls.equals(r.classLevel)) filtered.add(r);
        data.setAll(filtered);
    }

    private void filterQuestions() {
        String search = searchField.getText() == null
                      ? "" : searchField.getText().toLowerCase();
        String cls    = classFilter.getValue();

        List<QuestionRow> source = selectedSubject != null
            ? subjectQuestionMap.getOrDefault(
                selectedSubject, Collections.emptyList())
            : allData;

        ObservableList<QuestionRow> filtered =
            FXCollections.observableArrayList();

        for (QuestionRow q : source) {
            boolean matchSearch = search.isBlank()
                || q.getText().toLowerCase().contains(search)
                || q.getSubject().toLowerCase().contains(search);
            boolean matchClass = cls == null || "All".equals(cls)
                || cls.equals(q.classLevel);
            if (matchSearch && matchClass) filtered.add(q);
        }

        questionTable.setItems(filtered);
        setStatus(filtered.size() + " questions shown", false);
    }

    // =====================================================================
    //  APPROVE / UNAPPROVE / DELETE
    // =====================================================================
    @FXML
    private void approveSelected() {
        if (!canApprove()) {
            setStatus("Access denied - approval requires Exam Officer or Admin.", true);
            return;
        }
        QuestionRow r = questionTable.getSelectionModel().getSelectedItem();
        if (r == null) { setStatus("Select a question first", true); return; }
        setApproval(r.id, true);
        AuthService.logAudit("QUESTION_APPROVE", "questions", r.id);
        setStatus("Approved: " +
            r.text.substring(0, Math.min(60, r.text.length())), false);
        refreshQuestions();
    }

    @FXML
    private void unapproveSelected() {
        if (!canApprove()) {
            setStatus("Access denied - approval requires Exam Officer or Admin.", true);
            return;
        }
        QuestionRow r = questionTable.getSelectionModel().getSelectedItem();
        if (r == null) { setStatus("Select a question first", true); return; }
        setApproval(r.id, false);
        AuthService.logAudit("QUESTION_UNAPPROVE", "questions", r.id);
        setStatus("Unapproved: " +
            r.text.substring(0, Math.min(60, r.text.length())), false);
        refreshQuestions();
    }

    private void setApproval(String id, boolean approved) {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE questions SET is_approved=? WHERE id=?")) {
            ps.setBoolean(1, approved);
            AuthService.setUuid(ps, 2, id, c);
            ps.executeUpdate();
        } catch (Exception e) {
            setStatus("Error: " + e.getMessage(), true);
        }
    }

    @FXML
    private void deleteSelected() {
        if (!canDelete()) {
            setStatus("Access denied - only admins can delete questions.", true);
            return;
        }
        QuestionRow r = questionTable.getSelectionModel().getSelectedItem();
        if (r == null) { setStatus("Select a question to delete", true); return; }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "Delete this question?\n" +
            r.text.substring(0, Math.min(80, r.text.length())),
            ButtonType.YES, ButtonType.NO);
        confirm.showAndWait().ifPresent(btn -> {
            if (btn != ButtonType.YES) return;
            try (Connection c = DatabaseManager.getConnection()) {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM question_options WHERE question_id=?")) {
                    AuthService.setUuid(ps, 1, r.id, c);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM questions WHERE id=?")) {
                    AuthService.setUuid(ps, 1, r.id, c);
                    ps.executeUpdate();
                }
                AuthService.logAudit("QUESTION_DELETE", "questions", r.id);
                setStatus("Question deleted", false);
                refreshQuestions();
            } catch (Exception e) {
                setStatus("Cannot delete - may be used in an exam", true);
            }
        });
    }

    private void setStatus(String msg, boolean error) {
        if (statusLabel == null) return;
        statusLabel.setText(msg);
        statusLabel.setStyle(error
            ? "-fx-text-fill:#ef4444;"
            : "-fx-text-fill:#0f7a3a;");
    }
}

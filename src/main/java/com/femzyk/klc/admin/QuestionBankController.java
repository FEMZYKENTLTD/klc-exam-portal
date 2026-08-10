package com.femzyk.klc.admin;

import com.femzyk.klc.db.DatabaseManager;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class QuestionBankController {

    // ─── Subject TreeView ─────────────────────────────────────────────────────
    @FXML private TreeView<String>  subjectTree;

    // ─── Question Detail Table (shown when subject selected) ──────────────────
    @FXML private TableView<QuestionRow>           questionTable;
    @FXML private TableColumn<QuestionRow, String> colQuestion, colClass,
                                                    colDifficulty, colApproved;

    // ─── Toolbar ──────────────────────────────────────────────────────────────
    @FXML private TextField       searchField;
    @FXML private ComboBox<String> classFilter;
    @FXML private Label            statusLabel;

    private final ObservableList<QuestionRow> data    =
            FXCollections.observableArrayList();
    private final ObservableList<QuestionRow> allData =
            FXCollections.observableArrayList();

    // Map: subject name → list of question rows
    private final Map<String, List<QuestionRow>> subjectQuestionMap =
            new LinkedHashMap<>();

    private String selectedSubject = null;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS).build();
    private WebSocket webSocket;

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

    @FXML
    public void initialize() {
        // Table columns
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

        // TreeView selection listener
        if (subjectTree != null) {
            subjectTree.getSelectionModel()
                .selectedItemProperty().addListener((obs, ov, nv) -> {
                if (nv != null && nv.isLeaf()
                        && nv.getParent() != null
                        && nv.getParent().getParent() != null) {
                    // Leaf = class level node under subject
                    selectedSubject = nv.getParent().getValue();
                    showQuestionsForSubjectAndClass(
                        selectedSubject, nv.getValue().split(" ")[0]);
                } else if (nv != null && !nv.isLeaf()
                        && nv.getParent() != null) {
                    // Subject node clicked
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
        startRealtimeListener();
    }

    // =========================================================================
    //  LOAD ALL QUESTIONS AND BUILD TREE
    // =========================================================================
    @FXML
    public void refreshQuestions() {
        data.clear();
        allData.clear();
        subjectQuestionMap.clear();

        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT q.id, q.question_text, s.subject_name, " +
                 "q.class_level, q.difficulty, q.is_approved " +
                 "FROM questions q " +
                 "JOIN subjects s ON s.id = q.subject_id " +
                 "ORDER BY s.subject_name, q.class_level, q.created_at DESC")) {

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
                " subjects. Click a subject to view questions.", false);

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

            // Count per class
            Map<String, Long> classCount = new LinkedHashMap<>();
            for (QuestionRow r : rows) {
                classCount.merge(
                    r.classLevel == null ? "General" : r.classLevel,
                    1L, Long::sum);
            }

            long total = rows.size();
            long approved = rows.stream()
                .filter(r -> "Approved".equals(r.approved)).count();

            TreeItem<String> subjectItem = new TreeItem<>(
                subject + " (" + total + " total, " + approved + " approved)");

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

    // =========================================================================
    //  APPROVE / UNAPPROVE / DELETE
    // =========================================================================
    @FXML
    private void approveSelected() {
        QuestionRow r = questionTable.getSelectionModel().getSelectedItem();
        if (r == null) { setStatus("Select a question first", true); return; }
        setApproval(r.id, true);
        setStatus("Approved: " + r.text.substring(0, Math.min(60, r.text.length())), false);
        refreshQuestions();
    }

    @FXML
    private void unapproveSelected() {
        QuestionRow r = questionTable.getSelectionModel().getSelectedItem();
        if (r == null) { setStatus("Select a question first", true); return; }
        setApproval(r.id, false);
        setStatus("Unapproved: " + r.text.substring(0, Math.min(60, r.text.length())), false);
        refreshQuestions();
    }

    private void setApproval(String id, boolean approved) {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE questions SET is_approved=? WHERE id=?")) {
            ps.setBoolean(1, approved);
            ps.setString(2, id);
            ps.executeUpdate();
        } catch (Exception e) {
            setStatus("Error: " + e.getMessage(), true);
        }
    }

    @FXML
    private void deleteSelected() {
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
                    ps.setString(1, r.id); ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM questions WHERE id=?")) {
                    ps.setString(1, r.id); ps.executeUpdate();
                }
                setStatus("Question deleted", false);
                refreshQuestions();
            } catch (Exception e) {
                setStatus("Cannot delete - may be used in an exam", true);
            }
        });
    }

    private void startRealtimeListener() {
        try {
            String url =
                "wss://aqircycpctadgvbqsadf.supabase.co/realtime/v1/websocket" +
                "?apikey=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9" +
                ".eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImFxaXJjeWNwY3RhZGd2YnFzYWRmIiwi" +
                "cm9sZSI6ImFub24iLCJpYXQiOjE3ODIxNDM0OTMsImV4cCI6MjA5NzcxOTQ5M30" +
                ".mn9pn4bmx8R860K2KZx-MEe-G0U7o4ZYZxwwO6p7sjg&vsn=1.0.0";
            webSocket = client.newWebSocket(
                new Request.Builder().url(url).build(),
                new WebSocketListener() {
                    @Override
                    public void onMessage(WebSocket ws, String text) {
                        if (text.contains("questions"))
                            Platform.runLater(() -> refreshQuestions());
                    }
                });
        } catch (Exception ignored) {}
    }

    private void setStatus(String msg, boolean error) {
        if (statusLabel == null) return;
        statusLabel.setText(msg);
        statusLabel.setStyle(error
            ? "-fx-text-fill:#ef4444;"
            : "-fx-text-fill:#0f7a3a;");
    }

    public void cleanup() {
        if (webSocket != null) webSocket.close(1000, "Closing");
    }
}
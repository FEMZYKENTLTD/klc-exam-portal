package com.femzyk.klc.admin;

import com.femzyk.klc.auth.AuthService;
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
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class ExamManagerController {

    @FXML private TableView<ExamRow>           table;
    @FXML private TableColumn<ExamRow, String> colTitle, colSubject, colClass,
                                                colStatus, colSchedule;
    @FXML private ComboBox<String>  subjectBox, classBox, termBox,
                                    difficultyFilter;
    @FXML private TextField         titleField, durationField, topicFilter;
    @FXML private TextArea          instructionsArea;
    @FXML private DatePicker        startDate, endDate;
    @FXML private Spinner<Integer>  startHour, startMin, endHour, endMin,
                                    questionCountSpinner;
    @FXML private CheckBox          practiceCheck, feeGateCheck,
                                    negativeMarkCheck;
    @FXML private Label             status, statusLabel;

    private final ObservableList<ExamRow> data = FXCollections.observableArrayList();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS).build();
    private WebSocket webSocket;

    public static class ExamRow {
        String id, title, subject, classLevel, term, duration,
               examStatus, schedule;

        ExamRow(String id, String t, String s, String c,
                String tm, String d, String st, String sch) {
            this.id = id; title = t; subject = s; classLevel = c;
            term = tm; duration = d; examStatus = st; schedule = sch;
        }

        public String getTitle()      { return title; }
        public String getSubject()    { return subject; }
        public String getClassLevel() { return classLevel; }
        public String getStatus()     { return examStatus; }
        public String getSchedule()   { return schedule; }
    }

    @FXML
    public void initialize() {
        colTitle.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getTitle()));
        colSubject.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getSubject()));
        colClass.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getClassLevel()));
        colStatus.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getStatus()));
        if (colSchedule != null)
            colSchedule.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(
                    c.getValue().getSchedule()));

        table.setItems(data);

        classBox.getItems().addAll(
            "JSS1","JSS2","JSS3","SS1","SS2","SS3");
        termBox.getItems().addAll("1st","2nd","3rd");
        difficultyFilter.getItems().addAll("Any","Easy","Medium","Hard");
        difficultyFilter.setValue("Any");

        setupSpinner(startHour, 0, 23, 8);
        setupSpinner(startMin,  0, 59, 0);
        setupSpinner(endHour,   0, 23, 17);
        setupSpinner(endMin,    0, 59, 0);
        setupSpinner(questionCountSpinner, 1, 200, 40);

        loadSubjectsIntoBox();
        loadExams();
        startRealtimeListener();
    }

    private void setupSpinner(Spinner<Integer> sp, int min, int max, int def) {
        if (sp != null)
            sp.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(min, max, def));
    }

    private void loadSubjectsIntoBox() {
        // FIX: Use DISTINCT subject_name to avoid blank dropdown entries
        // Previous version joined subject_name + subject_code causing duplicates
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT DISTINCT subject_name FROM subjects " +
                 "WHERE is_active = TRUE " +
                 "ORDER BY subject_name")) {
            ResultSet rs = ps.executeQuery();
            subjectBox.getItems().clear();
            while (rs.next()) {
                String name = rs.getString(1);
                if (name != null && !name.isBlank())
                    subjectBox.getItems().add(name);
            }
        } catch (Exception ignored) {}
    }

    private void loadExams() {
        data.clear();
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT e.id, e.title, s.subject_name, e.class_level, " +
                 "e.term, e.duration_minutes, e.is_active, " +
                 "e.is_practice, e.start_at, e.end_at " +
                 "FROM exams e " +
                 "JOIN subjects s ON s.id = e.subject_id " +
                 "ORDER BY e.created_at DESC LIMIT 100")) {

            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                boolean isActive   = rs.getBoolean("is_active");
                boolean isPractice = rs.getBoolean("is_practice");
                String statusStr   = (isPractice ? "[PRACTICE] " : "") +
                    (isActive ? "Active" : "Inactive");

                String sch = "";
                if (rs.getTimestamp("start_at") != null)
                    sch = rs.getTimestamp("start_at")
                        .toLocalDateTime().toLocalDate() + " to " +
                        (rs.getTimestamp("end_at") == null ? "open" :
                         rs.getTimestamp("end_at")
                           .toLocalDateTime().toLocalDate().toString());

                data.add(new ExamRow(
                    rs.getString("id"),
                    rs.getString("title"),
                    rs.getString("subject_name"),
                    rs.getString("class_level"),
                    rs.getString("term"),
                    rs.getInt("duration_minutes") + " mins",
                    statusStr, sch));
            }
            setStatus("Loaded " + data.size() + " exams", false);

        } catch (Exception e) {
            setStatus("Error: " + e.getMessage(), true);
        }
    }

    @FXML
    private void createExam() {
        String subject = subjectBox.getValue();
        String cls     = classBox.getValue();
        String term    = termBox.getValue();
        String title   = titleField.getText().trim();
        String durStr  = durationField.getText().trim();

        if (subject == null || cls == null || term == null
                || title.isBlank() || durStr.isBlank()) {
            setStatus("Fill Subject, Class, Term, Title and Duration", true);
            return;
        }

        int duration;
        try { duration = Integer.parseInt(durStr); }
        catch (Exception e) {
            setStatus("Duration must be a number (e.g. 40)", true);
            return;
        }

        try (Connection c = DatabaseManager.getConnection()) {

            String subjectId = null;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT MIN(CAST(id AS VARCHAR(36))) FROM subjects " +
                    "WHERE subject_name = ? AND is_active = TRUE")) {
                ps.setString(1, subject);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) subjectId = rs.getString(1);
            }
            if (subjectId == null) {
                setStatus("Subject not found in database", true);
                return;
            }

            Timestamp startTs = buildTimestamp(
                startDate.getValue(),
                startHour == null ? 8  : startHour.getValue(),
                startMin  == null ? 0  : startMin.getValue());
            Timestamp endTs = buildTimestamp(
                endDate.getValue(),
                endHour == null ? 17 : endHour.getValue(),
                endMin  == null ? 0  : endMin.getValue());

            double  negMark  = negativeMarkCheck != null
                && negativeMarkCheck.isSelected() ? 0.25 : 0.0;
            boolean practice = practiceCheck != null
                && practiceCheck.isSelected();
            boolean feeGate  = feeGateCheck != null
                && feeGateCheck.isSelected();
            int     qCount   = questionCountSpinner == null
                ? 40 : questionCountSpinner.getValue();
            String  diff     = difficultyFilter == null
                || "Any".equals(difficultyFilter.getValue())
                ? null : difficultyFilter.getValue();
            String  topic    = topicFilter == null
                || topicFilter.getText().isBlank()
                ? null : topicFilter.getText().trim();

            String examId = UUID.randomUUID().toString();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO exams(id, subject_id, class_level, arm, " +
                    "term, session, title, instructions, duration_minutes, " +
                    "start_at, end_at, attempt_limit, is_practice, fee_gate, " +
                    "negative_marking, is_active, created_by) " +
                    "VALUES(?,?,?,NULL,?,?,?,?,?,?,?,1,?,?,?,TRUE,?)")) {
                ps.setString(1, examId);
                ps.setString(2, subjectId);
                ps.setString(3, cls);
                ps.setString(4, term);
                ps.setString(5, "2024/2025");
                ps.setString(6, title);
                ps.setString(7, instructionsArea == null
                    ? "" : instructionsArea.getText());
                ps.setInt(8, duration);
                ps.setTimestamp(9, startTs);
                ps.setTimestamp(10, endTs);
                ps.setBoolean(11, practice);
                ps.setBoolean(12, feeGate);
                ps.setDouble(13, negMark);
                ps.setString(14, AuthService.Session.userId);
                ps.executeUpdate();
            }

            int attached = attachQuestionsFromPool(
                c, examId, subjectId, cls, diff, topic, qCount);

            AuthService.logAudit("EXAM_CREATE", "exams", examId);
            setStatus("Exam created! " + attached + " questions attached.", false);
            clearForm();
            loadExams();

        } catch (Exception e) {
            setStatus("Error: " + e.getMessage(), true);
            e.printStackTrace();
        }
    }

    private int attachQuestionsFromPool(
            Connection c, String examId, String subjectId,
            String cls, String difficulty, String topic, int count)
            throws SQLException {

        StringBuilder sql = new StringBuilder(
            "SELECT id FROM questions WHERE subject_id = ? " +
            "AND is_approved = TRUE");
        if (cls        != null) sql.append(" AND class_level = ?");
        if (difficulty != null) sql.append(" AND difficulty = ?");
        if (topic      != null) sql.append(" AND LOWER(topic) LIKE ?");
        sql.append(" ORDER BY RANDOM() LIMIT ?");

        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setString(idx++, subjectId);
            if (cls        != null) ps.setString(idx++, cls);
            if (difficulty != null) ps.setString(idx++, difficulty);
            if (topic      != null)
                ps.setString(idx++, "%" + topic.toLowerCase() + "%");
            ps.setInt(idx, count);

            ResultSet rs    = ps.executeQuery();
            int order = 1, attached = 0;
            while (rs.next()) {
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO exam_questions(" +
                        "exam_id, question_id, question_order) " +
                        "VALUES(?,?,?)")) {
                    ins.setString(1, examId);
                    ins.setString(2, rs.getString(1));
                    ins.setInt(3, order++);
                    ins.executeUpdate();
                    attached++;
                } catch (Exception ignored) {}
            }
            return attached;
        }
    }

    private Timestamp buildTimestamp(LocalDate date, int hour, int min) {
        if (date == null) return null;
        return Timestamp.valueOf(date.atTime(hour, min));
    }

    @FXML
    private void toggleActive() {
        ExamRow r = table.getSelectionModel().getSelectedItem();
        if (r == null) { setStatus("Select an exam first", true); return; }
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE exams SET is_active = NOT is_active WHERE id = ?")) {
            ps.setString(1, r.id);
            ps.executeUpdate();
            setStatus("Toggled active status: " + r.title, false);
            loadExams();
        } catch (Exception e) {
            setStatus("Error: " + e.getMessage(), true);
        }
    }

    // =========================================================================
    //  GLOBAL PRACTICE TOGGLE — Super Admin enables/disables ALL practice exams
    // =========================================================================
    @FXML
    private void enableAllPractice() {
        if (!AuthService.isSuperAdmin()) {
            setStatus("Only Super Admin can enable/disable all practice exams.",
                true);
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "Enable ALL practice exams for all students?\n" +
            "Students will be able to access practice mode.",
            ButtonType.YES, ButtonType.NO);
        confirm.setTitle("Enable All Practice Exams");
        confirm.showAndWait().ifPresent(btn -> {
            if (btn != ButtonType.YES) return;
            try (Connection c = DatabaseManager.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                     "UPDATE exams SET is_active = TRUE " +
                     "WHERE is_practice = TRUE")) {
                int count = ps.executeUpdate();
                AuthService.logAudit("PRACTICE_ENABLE_ALL", "exams", null);
                setStatus("Enabled " + count + " practice exams globally.", false);
                loadExams();
            } catch (Exception e) {
                setStatus("Error: " + e.getMessage(), true);
            }
        });
    }

    @FXML
    private void disableAllPractice() {
        if (!AuthService.isSuperAdmin()) {
            setStatus("Only Super Admin can enable/disable all practice exams.",
                true);
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "Disable ALL practice exams for all students?\n" +
            "Students will NOT be able to access practice mode.",
            ButtonType.YES, ButtonType.NO);
        confirm.setTitle("Disable All Practice Exams");
        confirm.showAndWait().ifPresent(btn -> {
            if (btn != ButtonType.YES) return;
            try (Connection c = DatabaseManager.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                     "UPDATE exams SET is_active = FALSE " +
                     "WHERE is_practice = TRUE")) {
                int count = ps.executeUpdate();
                AuthService.logAudit("PRACTICE_DISABLE_ALL", "exams", null);
                setStatus("Disabled " + count + " practice exams globally.", false);
                loadExams();
            } catch (Exception e) {
                setStatus("Error: " + e.getMessage(), true);
            }
        });
    }

    @FXML
    private void cloneExam() {
        ExamRow r = table.getSelectionModel().getSelectedItem();
        if (r == null) { setStatus("Select an exam to clone", true); return; }
        try (Connection c = DatabaseManager.getConnection()) {
            String newId = UUID.randomUUID().toString();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO exams(id, subject_id, class_level, arm, " +
                    "term, session, title, instructions, duration_minutes, " +
                    "attempt_limit, is_practice, fee_gate, negative_marking, " +
                    "is_active, created_by) " +
                    "SELECT ?, subject_id, class_level, arm, " +
                    "term, session, title || ' (Copy)', instructions, " +
                    "duration_minutes, attempt_limit, is_practice, fee_gate, " +
                    "negative_marking, FALSE, created_by " +
                    "FROM exams WHERE id = ?")) {
                ps.setString(1, newId);
                ps.setString(2, r.id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO exam_questions(exam_id, question_id, " +
                    "question_order) " +
                    "SELECT ?, question_id, question_order " +
                    "FROM exam_questions WHERE exam_id = ?")) {
                ps.setString(1, newId);
                ps.setString(2, r.id);
                ps.executeUpdate();
            }
            setStatus("Exam cloned (Inactive by default)", false);
            loadExams();
        } catch (Exception e) {
            setStatus("Clone error: " + e.getMessage(), true);
        }
    }

    @FXML
    private void refreshExams() {
        loadSubjectsIntoBox();
        loadExams();
    }

    private void clearForm() {
        if (titleField      != null) titleField.clear();
        if (durationField   != null) durationField.clear();
        if (instructionsArea!= null) instructionsArea.clear();
        subjectBox.setValue(null);
        classBox.setValue(null);
        termBox.setValue(null);
        if (startDate != null) startDate.setValue(null);
        if (endDate   != null) endDate.setValue(null);
    }

    private void startRealtimeListener() {
        try {
            // KLC v1.0 SECURITY FIX: project ref + anon key are no longer
            // hardcoded in source. Configure supabase.url + supabase.key in
            // config.properties; realtime is skipped when absent (the
            // polling auto-refresh still works).
            String supaUrl = com.femzyk.klc.util.ConfigService
                .get("supabase.url", "");
            String supaKey = com.femzyk.klc.util.ConfigService
                .get("supabase.key", "");
            if (supaUrl.isBlank() || supaKey.isBlank()
                    || !supaUrl.startsWith("http")) {
                System.out.println("[Realtime] supabase.url/key not "
                    + "configured - realtime listener skipped");
                return;
            }
            String url = supaUrl.replaceFirst("^https?://", "wss://")
                + "/realtime/v1/websocket?apikey=" + supaKey + "&vsn=1.0.0";
            webSocket = client.newWebSocket(
                new Request.Builder().url(url).build(),
                new WebSocketListener() {
                    @Override
                    public void onMessage(WebSocket ws, String text) {
                        if (text.contains("exams"))
                            Platform.runLater(() -> loadExams());
                    }
                });
        } catch (Exception ignored) {}
    }

    private void setStatus(String msg, boolean error) {
        Label lbl = status != null ? status : statusLabel;
        if (lbl == null) return;
        lbl.setText(msg);
        lbl.setStyle(error
            ? "-fx-text-fill:#ef4444; -fx-font-weight:bold;"
            : "-fx-text-fill:#0f7a3a; -fx-font-weight:bold;");
    }

    public void cleanup() {
        if (webSocket != null) webSocket.close(1000, "Closing");
    }
}
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
import java.util.concurrent.TimeUnit;

public class ResultsViewController {

    @FXML private TableView<ResultRow>              table;
    @FXML private TableColumn<ResultRow, String>    colName, colAdm, colSubject,
                                                     colScore, colGrade,
                                                     colPos, colDate;
    @FXML private TextField                          searchField;
    @FXML private ComboBox<String>                   termBox;
    @FXML private Label                              status;

    private final ObservableList<ResultRow> data    =
            FXCollections.observableArrayList();
    private final ObservableList<ResultRow> allData =
            FXCollections.observableArrayList();

    private final OkHttpClient client = new OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS).build();
    private WebSocket webSocket;

    public static class ResultRow {
        String userId, student, admission, subject, score, grade, position,
               date, term, session;

        ResultRow(String userId, String s, String adm, String sub,
                  String sc, String g, String pos, String d,
                  String term, String session) {
            this.userId = userId; student = s; admission = adm; subject = sub;
            score = sc; grade = g; position = pos; date = d;
            this.term = term; this.session = session;
        }

        public String getUserId()     { return userId; }
        public String getStudent()    { return student; }
        public String getAdmission()  { return admission == null ? "-" : admission; }
        public String getSubject()    { return subject; }
        public String getScore()      { return score; }
        public String getGrade()      { return grade; }
        public String getPosition()   { return position; }
        public String getDate()       { return date; }
        public String getTerm()       { return term == null || term.isBlank() ? "1st" : term; }
        public String getSession()    { return session == null || session.isBlank() ? "" : session; }
    }

    @FXML
    public void initialize() {
        colName.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getStudent()));
        if (colAdm != null)
            colAdm.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(
                    c.getValue().getAdmission()));
        colSubject.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getSubject()));
        colScore.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getScore()));
        colGrade.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getGrade()));
        if (colPos != null)
            colPos.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(
                    c.getValue().getPosition()));
        if (colDate != null)
            colDate.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(
                    c.getValue().getDate()));

        termBox.getItems().addAll("All","1st","2nd","3rd");
        termBox.setValue("All");
        termBox.valueProperty().addListener((o, ov, nv) -> loadResults());

        if (searchField != null)
            searchField.textProperty().addListener(
                (o, ov, nv) -> filterResults());

        table.setItems(data);
        loadResults();
        startRealtimeListener();
    }

    // =========================================================================
    //  LOAD - called by initialize and Refresh button
    // =========================================================================
    @FXML
    public void loadResults() {
        data.clear();
        allData.clear();

        try (java.sql.Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT u.id, u.full_name, sp.admission_no, s.subject_name, " +
                 "r.percentage, r.grade, r.position, r.created_at, " +
                 "e.term, e.session " +
                 "FROM results r " +
                 "JOIN users u ON u.id = r.student_id " +
                 "LEFT JOIN student_profiles sp ON sp.user_id = u.id " +
                 "JOIN exams e ON e.id = r.exam_id " +
                 "JOIN subjects s ON s.id = e.subject_id " +
                 "ORDER BY r.created_at DESC LIMIT 300")) {

            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Timestamp ts = rs.getTimestamp(8);
                ResultRow row = new ResultRow(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    String.format("%.1f%%", rs.getDouble(5)),
                    rs.getString(6) == null ? "-" : rs.getString(6),
                    rs.getString(7) == null ? "-" : rs.getString(7),
                    ts == null ? "-"
                        : ts.toLocalDateTime().toLocalDate().toString(),
                    rs.getString(9),
                    rs.getString(10)
                );
                data.add(row);
                allData.add(row);
            }

            if (status != null)
                status.setText("Loaded " + data.size() + " results");

        } catch (Exception e) {
            if (status != null) {
                status.setText("Error: " + e.getMessage());
                status.setStyle("-fx-text-fill:#ef4444;");
            }
            e.printStackTrace();
        }
    }

    private void filterResults() {
        String search = searchField == null ? ""
            : searchField.getText().toLowerCase();
        ObservableList<ResultRow> filtered =
            FXCollections.observableArrayList();
        for (ResultRow r : allData) {
            if (search.isBlank()
                    || r.getStudent().toLowerCase().contains(search)
                    || r.getSubject().toLowerCase().contains(search))
                filtered.add(r);
        }
        table.setItems(filtered);
    }

    // =========================================================================
    //  EXPORT / NOTIFY - real implementations (ReportCardService, PDF
    //  generators and EmailService). Every action reports the produced file
    //  path or a precise error.
    // =========================================================================
    @FXML
    private void exportReportCard() {
        ResultRow r = table.getSelectionModel().getSelectedItem();
        if (r == null) {
            showInfo("Select a student result row first.");
            return;
        }
        // "All" carries no single term - use the term the exam was written in.
        String term = "All".equals(termBox.getValue())
                    ? r.getTerm() : termBox.getValue();
        String session = r.getSession();
        try {
            String pdfPath = com.femzyk.klc.util.ReportCardService
                .generateReportCard(r.getUserId(), term, session);
            if (pdfPath != null) {
                showInfo("Report Card generated:\n" + pdfPath);
            } else {
                showError("Report Card could not be generated for "
                    + r.getStudent()
                    + " (" + term + " term"
                    + (session.isBlank() ? "" : ", " + session)
                    + ").\nHas this student got published results in that term?");
            }
        } catch (Exception e) {
            showError("Report Card export failed: " + e.getMessage());
        }
    }

    @FXML
    private void exportTranscript() {
        ResultRow r = table.getSelectionModel().getSelectedItem();
        if (r == null) { showInfo("Select a student first."); return; }
        try {
            String pdfPath = com.femzyk.klc.util.ReportCardService
                .generateTranscript(r.getUserId());
            if (pdfPath != null) {
                showInfo("Full transcript PDF generated:\n" + pdfPath);
            } else {
                showError("Transcript could not be generated for "
                    + r.getStudent() + ".");
            }
        } catch (Exception e) {
            showError("Transcript export failed: " + e.getMessage());
        }
    }

    @FXML
    private void exportGraduation() {
        ResultRow r = table.getSelectionModel().getSelectedItem();
        if (r == null) { showInfo("Select an SS3 student result row first."); return; }
        try {
            // Confirm the student is actually in SS3 before issuing a
            // graduation certificate.
            String cls = null;
            try (java.sql.Connection c = DatabaseManager.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                     "SELECT class_level FROM student_profiles WHERE user_id = ?")) {
                ps.setString(1, r.getUserId());
                java.sql.ResultSet rs = ps.executeQuery();
                if (rs.next()) cls = rs.getString(1);
            }
            if (cls == null || !cls.toUpperCase().contains("SS3")) {
                showError("Graduation certificates are issued to SS3 completers only.\n"
                    + r.getStudent() + " is in "
                    + (cls == null ? "an unknown class" : cls) + ".");
                return;
            }
            String out = com.femzyk.klc.util.GraduationCertificatePdf
                .generate(r.getUserId(), null);
            showInfo("Graduation certificate generated:\n" + out);
        } catch (Exception e) {
            showError("Graduation certificate export failed: " + e.getMessage());
        }
    }

    @FXML
    private void notifyResult() {
        ResultRow r = table.getSelectionModel().getSelectedItem();
        if (r == null) { showInfo("Select a student result to notify."); return; }
        try {
            String email = null;
            try (java.sql.Connection c = DatabaseManager.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                     "SELECT email FROM users WHERE id = ?")) {
                ps.setString(1, r.getUserId());
                java.sql.ResultSet rs = ps.executeQuery();
                if (rs.next()) email = rs.getString(1);
            }
            if (email == null || email.isBlank()) {
                showError("No email address on file for " + r.getStudent() + ".");
                return;
            }
            double score;
            try {
                score = Double.parseDouble(
                    r.getScore().replace("%", "").trim());
            } catch (NumberFormatException nfe) { score = 0; }
            com.femzyk.klc.util.EmailService.sendResultNotification(
                email, r.getStudent(), r.getSubject(), score);
            if (com.femzyk.klc.util.EmailService.isEnabled()) {
                showInfo("Result notification sent to:\n" + email
                    + "\n\nSubject: " + r.getSubject()
                    + "   Score: " + r.getScore());
            } else {
                // SMTP not configured - send() auto-queued it in
                // notification_queue for the next flush (Notification screen).
                showInfo("SMTP is not configured on this PC, so the "
                    + "notification was QUEUED for automatic delivery:\n"
                    + email + "\n\nSubject: " + r.getSubject()
                    + "   Score: " + r.getScore()
                    + "\n\nQueue: Notifications screen / notification_queue "
                    + "(flushes when SMTP is set).");
            }
        } catch (Exception e) {
            showError("Result notification failed: " + e.getMessage()
                + "\n\nSMTP must be configured (smtp.* in config.properties).");
        }
    }

    private void showInfo(String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, msg);
        a.setTitle("KLC CBT");
        a.getDialogPane().setPrefWidth(420);
        a.showAndWait();
    }

    private void showError(String msg) {
        Alert a = new Alert(Alert.AlertType.WARNING, msg);
        a.setTitle("KLC CBT");
        a.getDialogPane().setPrefWidth(420);
        a.showAndWait();
    }

    private void startRealtimeListener() {
        try {
            // KLC v1.0 SECURITY FIX: project ref + anon key are no longer
            // hardcoded in source. Configure supabase.url + supabase.key in
            // config.properties; realtime is skipped when absent (the
            // manual refresh still works).
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
                        if (text.contains("results"))
                            Platform.runLater(() -> loadResults());
                    }
                });
        } catch (Exception ignored) {}
    }

    public void cleanup() {
        if (webSocket != null) webSocket.close(1000, "Closing");
    }
}
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
        String student, admission, subject, score, grade, position, date;

        ResultRow(String s, String adm, String sub,
                  String sc, String g, String pos, String d) {
            student = s; admission = adm; subject = sub;
            score = sc; grade = g; position = pos; date = d;
        }

        public String getStudent()    { return student; }
        public String getAdmission()  { return admission == null ? "-" : admission; }
        public String getSubject()    { return subject; }
        public String getScore()      { return score; }
        public String getGrade()      { return grade; }
        public String getPosition()   { return position; }
        public String getDate()       { return date; }
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
                 "SELECT u.full_name, sp.admission_no, s.subject_name, " +
                 "r.percentage, r.grade, r.position, r.created_at " +
                 "FROM results r " +
                 "JOIN users u ON u.id = r.student_id " +
                 "LEFT JOIN student_profiles sp ON sp.user_id = u.id " +
                 "JOIN exams e ON e.id = r.exam_id " +
                 "JOIN subjects s ON s.id = e.subject_id " +
                 "ORDER BY r.created_at DESC LIMIT 300")) {

            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Timestamp ts = rs.getTimestamp(7);
                ResultRow row = new ResultRow(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    String.format("%.1f%%", rs.getDouble(4)),
                    rs.getString(5) == null ? "-" : rs.getString(5),
                    rs.getString(6) == null ? "-" : rs.getString(6),
                    ts == null ? "-"
                        : ts.toLocalDateTime().toLocalDate().toString()
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
    //  EXPORT / NOTIFY - stub implementations (full PDF in ReportCardService)
    // =========================================================================
    @FXML
    private void exportReportCard() {
        ResultRow r = table.getSelectionModel().getSelectedItem();
        if (r == null) {
            showInfo("Select a student result row first.");
            return;
        }
        showInfo("Report Card PDF generation for:\n" + r.getStudent() +
                 "\nSubject: " + r.getSubject() +
                 "\nScore: " + r.getScore() +
                 "\n\nFull PDF generation is available in the " +
                 "Report Card Generator section.");
    }

    @FXML
    private void exportTranscript() {
        ResultRow r = table.getSelectionModel().getSelectedItem();
        if (r == null) { showInfo("Select a student first."); return; }
        showInfo("Full JSS1-SS3 Transcript for:\n" + r.getStudent() +
                 "\n\nFull transcript PDF is in the Report Card Generator.");
    }

    @FXML
    private void exportGraduation() {
        showInfo("Graduation Certificate generation is available for SS3 completers.\n" +
                 "Go to: Report Card Generator → Graduation Certificate.");
    }

    @FXML
    private void notifyResult() {
        ResultRow r = table.getSelectionModel().getSelectedItem();
        if (r == null) { showInfo("Select a student result to notify."); return; }
        showInfo("Result notification queued for:\n" + r.getStudent() +
                 "\nConfigure SMTP in config.properties for email delivery.");
    }

    private void showInfo(String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, msg);
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
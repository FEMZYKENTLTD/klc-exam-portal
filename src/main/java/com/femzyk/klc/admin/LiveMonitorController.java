package com.femzyk.klc.admin;

import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.db.DatabaseManager;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;

// FIX: Explicit okhttp3 imports � removes Connection ambiguity
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

public class LiveMonitorController {

    @FXML private TableView<LiveRow> table;
    @FXML private TableColumn<LiveRow, String> colName, colAdm, colExam,
                                                colStarted, colStrikes, colStatus, colAnswered;
    @FXML private Label status;
    @FXML private CheckBox autoRefreshBox;

    private final ObservableList<LiveRow> data = FXCollections.observableArrayList();
    private WebSocket webSocket;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    public static class LiveRow {
        String attemptId, name, admission, exam, started, strikes, status;
        int answered;

        LiveRow(String aid, String n, String a, String e,
                String st, String str, String s, int ans) {
            attemptId = aid; name = n; admission = a; exam = e;
            started = st; strikes = str; status = s; answered = ans;
        }

        public String getName()      { return name; }
        public String getAdmission() { return admission; }
        public String getExam()      { return exam; }
        public String getStarted()   { return started; }
        public String getStrikes()   { return strikes; }
        public String getStatus()    { return status; }
        public String getAnswered()  { return String.valueOf(answered); }
    }

    @FXML
    public void initialize() {
        colName.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(c.getValue().getName()));
        colAdm.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(c.getValue().getAdmission()));
        colExam.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(c.getValue().getExam()));
        colStarted.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(c.getValue().getStarted()));
        colStrikes.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(c.getValue().getStrikes()));
        colStatus.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(c.getValue().getStatus()));
        colAnswered.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(c.getValue().getAnswered()));

        table.setItems(data);
        loadLive();
        connectToSupabaseRealtime();
        autoRefreshBox.setSelected(true);
    }

    private void connectToSupabaseRealtime() {
        // KLC v1.0 SECURITY FIX: project ref + anon key are no longer
        // hardcoded in source. Configure supabase.url + supabase.key in
        // config.properties; realtime is skipped when absent (the polling
        // auto-refresh still works).
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

        Request request = new Request.Builder().url(url).build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                System.out.println("[Realtime] Connected to Supabase");
                ws.send("{\"topic\":\"realtime:public:exam_attempts\"," +
                        "\"event\":\"phx_join\",\"payload\":{},\"ref\":\"1\"}");
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                if (text.contains("exam_attempts")) {
                    Platform.runLater(() -> loadLive());
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                System.out.println("[Realtime] Connection failed: " + t.getMessage());
            }
        });
    }

    @FXML
    private void loadLive() {
        data.clear();
        try (java.sql.Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                SELECT ea.id, u.full_name, sp.admission_no,
                       s.subject_code || ' - ' || e.class_level AS exam_title,
                       ea.started_at, ea.strike_count, ea.status,
                       (SELECT COUNT(*) FROM attempt_answers aa WHERE aa.attempt_id = ea.id) AS answered
                FROM exam_attempts ea
                JOIN users u ON u.id = ea.student_id
                LEFT JOIN student_profiles sp ON sp.user_id = u.id
                JOIN exams e ON e.id = ea.exam_id
                JOIN subjects s ON s.id = e.subject_id
                WHERE ea.status IN ('IN_PROGRESS', 'MALPRACTICE')
                   OR ea.started_at > now() - interval '6 hours'
                ORDER BY ea.started_at DESC LIMIT 200
             """)) {

            ResultSet rs = ps.executeQuery();
            int live = 0;

            while (rs.next()) {
                String st = rs.getString(7);
                if ("IN_PROGRESS".equals(st)) live++;

                data.add(new LiveRow(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getTimestamp(5) == null ? "" :
                        rs.getTimestamp(5).toLocalDateTime().toLocalTime().toString(),
                    rs.getInt(6) + "/3",
                    st,
                    rs.getInt(8)
                ));
            }

            status.setText("Live now: " + live + " | Total recent: " + data.size() +
                           " | " + LocalDateTime.now().toLocalTime());

        } catch (Exception e) {
            status.setText("Error: " + e.getMessage());
        }
    }

    @FXML
    private void forceSubmit() {
        LiveRow r = table.getSelectionModel().getSelectedItem();
        if (r == null) { status.setText("Select a student"); return; }

        if (!AuthService.isSuperAdmin()) {
            new Alert(Alert.AlertType.WARNING,
                "Only Super Admin can Force Submit").show();
            return;
        }

        try (java.sql.Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE exam_attempts SET status='SUBMITTED', " +
                 "submitted_at=now() WHERE id=?")) {

            ps.setObject(1, java.util.UUID.fromString(r.attemptId));
            ps.executeUpdate();
            status.setText("Force submitted: " + r.name);
            loadLive();

        } catch (Exception e) {
            status.setText(e.getMessage());
        }
    }

    @FXML
    private void extendTime() {
        LiveRow r = table.getSelectionModel().getSelectedItem();
        if (r == null) return;

        TextInputDialog d = new TextInputDialog("10");
        d.setHeaderText("Extend time (minutes) for " + r.name);
        d.showAndWait().ifPresent(mins ->
            status.setText("Time extension logged: +" + mins + " min for " + r.name));
    }

    public void cleanup() {
        if (webSocket != null) webSocket.close(1000, "Closing");
    }
}
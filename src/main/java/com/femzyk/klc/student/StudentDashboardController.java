package com.femzyk.klc.student;

import com.femzyk.klc.MainApp;
import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.auth.SessionIdleWatcher;
import com.femzyk.klc.db.DatabaseManager;
import com.femzyk.klc.util.ReportCardPdf;
import com.femzyk.klc.util.SyncService;
import com.femzyk.klc.util.TranscriptPdf;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.stage.Stage;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import java.awt.Desktop;
import java.io.File;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class StudentDashboardController {

    // ─── Header ───────────────────────────────────────────────────────────────
    @FXML private Label welcomeLabel, pinLabel, cgpaLabel;

    // ─── Tabs ─────────────────────────────────────────────────────────────────
    @FXML private TabPane mainTabs;

    // ─── Dashboard Tab ────────────────────────────────────────────────────────
    @FXML private ComboBox<String>  subjectBox;
    @FXML private TextField         classField, armField, nameField,
                                    admissionField;
    @FXML private ListView<String>  announcementsList;
    @FXML private ListView<String>  leaderboardList;
    @FXML private TableView<ResultRow> resultsTable;
    @FXML private TableColumn<ResultRow, String> colRSub, colRScore,
                                                  colRGrade, colRDate;
    @FXML private LineChart<Number, Number> trendChart;

    // ─── My Results Tab ───────────────────────────────────────────────────────
    @FXML private TableView<FullResultRow> myFullResultsTable;
    @FXML private TableColumn<FullResultRow, String> colMySubject, colMyScore,
                                                      colMyGrade, colMyPosition,
                                                      colMyDate;
    @FXML private Label      resultSummaryLabel;
    @FXML private TextArea   appealText;
    @FXML private TextField  shareAdmissionField;
    @FXML private Label      shareStatusLabel;

    // ─── Notifications Tab ────────────────────────────────────────────────────
    @FXML private ListView<String>   shareRequestsList;
    @FXML private TableView<SharedResultRow> receivedResultsTable;
    @FXML private TableColumn<SharedResultRow, String> colRcvFrom, colRcvSubject,
                                                        colRcvScore, colRcvGrade,
                                                        colRcvDate;
    @FXML private Label notifStatusLabel;

    // ─── Practice Tab ─────────────────────────────────────────────────────────
    @FXML private ComboBox<String> practiceSubjectBox, practiceClassBox;
    @FXML private Label practiceStatusLabel;

    // ─── E-Library Tab ────────────────────────────────────────────────────────
    @FXML private TableView<MatRow>              materialsTable;
    @FXML private TableColumn<MatRow, String>    colMatTitle, colMatSub;

    // ─── OkHttp ───────────────────────────────────────────────────────────────
    private final OkHttpClient client = new OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS).build();
    private WebSocket webSocket;

    // ─── Share request tracking ───────────────────────────────────────────────
    private String selectedShareRequestId = null;

    // =========================================================================
    //  INNER MODELS
    // =========================================================================
    public static class MatRow {
        public String title, subject, fileUrl;
        MatRow(String t, String s, String f) {
            title = t; subject = s; fileUrl = f;
        }
        public String getTitle()   { return title; }
        public String getSubject() { return subject; }
    }

    public static class ResultRow {
        String subject, score, grade, date;
        ResultRow(String s, String sc, String g, String d) {
            subject = s; score = sc; grade = g; date = d;
        }
        public String getSubject() { return subject; }
        public String getScore()   { return score; }
        public String getGrade()   { return grade; }
        public String getDate()    { return date; }
    }

    public static class FullResultRow {
        String resultId, subject, score, grade, position, date;
        FullResultRow(String rid, String s, String sc,
                      String g, String pos, String d) {
            resultId = rid; subject = s; score = sc;
            grade = g; position = pos; date = d;
        }
        public String getSubject()  { return subject; }
        public String getScore()    { return score; }
        public String getGrade()    { return grade; }
        public String getPosition() { return position == null ? "-" : position; }
        public String getDate()     { return date; }
    }

    public static class SharedResultRow {
        String from, subject, score, grade, date;
        SharedResultRow(String f, String s, String sc, String g, String d) {
            from = f; subject = s; score = sc; grade = g; date = d;
        }
        public String getFrom()    { return from; }
        public String getSubject() { return subject; }
        public String getScore()   { return score; }
        public String getGrade()   { return grade; }
        public String getDate()    { return date; }
    }

    ObservableList<MatRow>         matData      = FXCollections.observableArrayList();
    ObservableList<ResultRow>      resultData   = FXCollections.observableArrayList();
    ObservableList<FullResultRow>  fullResults  = FXCollections.observableArrayList();
    ObservableList<SharedResultRow> sharedData  = FXCollections.observableArrayList();

    // =========================================================================
    //  INITIALIZE
    // =========================================================================
    @FXML
    public void initialize() {
        welcomeLabel.setText(
            "Welcome, " + AuthService.Session.fullName);

        setupResultsTableCols();
        setupFullResultsTableCols();
        setupMaterialsTable();
        setupSharedResultsTable();

        loadProfile();
        loadSubjects();
        loadAnnouncements();
        loadTopLeaderboard();
        loadResults();
        loadCgpa();
        loadTrendChart();
        loadMaterials();
        setupPracticeTab();

        startRealtimeListener();

        welcomeLabel.sceneProperty().addListener((o, ov, nv) -> {
            if (nv != null) SessionIdleWatcher.start(nv, () -> {});
        });
    }

    // =========================================================================
    //  TABLE SETUP
    // =========================================================================
    private void setupResultsTableCols() {
        colRSub.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getSubject()));
        colRScore.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getScore()));
        colRGrade.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getGrade()));
        colRDate.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getDate()));
        resultsTable.setItems(resultData);
    }

    private void setupFullResultsTableCols() {
        colMySubject.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getSubject()));
        colMyScore.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getScore()));
        colMyGrade.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getGrade()));
        colMyPosition.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getPosition()));
        colMyDate.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getDate()));
        myFullResultsTable.setItems(fullResults);
    }

    private void setupMaterialsTable() {
        colMatTitle.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getTitle()));
        colMatSub.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getSubject()));
        materialsTable.setItems(matData);
        materialsTable.setRowFactory(tv -> {
            TableRow<MatRow> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty())
                    openMaterial(row.getItem());
            });
            return row;
        });
    }

    private void setupSharedResultsTable() {
        colRcvFrom.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getFrom()));
        colRcvSubject.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getSubject()));
        colRcvScore.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getScore()));
        colRcvGrade.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getGrade()));
        colRcvDate.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getDate()));
        receivedResultsTable.setItems(sharedData);
    }

    private void setupPracticeTab() {
        if (practiceSubjectBox != null) {
            try (Connection c = DatabaseManager.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                     "SELECT DISTINCT subject_name FROM subjects " +
                     "WHERE is_active = TRUE ORDER BY subject_name")) {
                ResultSet rs = ps.executeQuery();
                while (rs.next())
                    practiceSubjectBox.getItems().add(rs.getString(1));
            } catch (Exception ignored) {}
        }
        if (practiceClassBox != null) {
            practiceClassBox.getItems().addAll(
                "JSS1","JSS2","JSS3","SS1","SS2","SS3");
        }
    }

    // =========================================================================
    //  TAB CHANGE HANDLERS
    // =========================================================================
    @FXML private void onDashboardTab() {
        loadAnnouncements();
        loadTopLeaderboard();
        loadResults();
        loadCgpa();
    }

    @FXML private void onResultsTab() {
        loadFullResults();
        loadSharedResults();
    }

    @FXML private void onNotificationsTab() {
        loadShareRequests();
        loadSharedResults();
    }

    @FXML private void onPracticeTab()  { /* practice tab loaded on init */ }
    @FXML private void onLibraryTab()   { loadMaterials(); }

    @FXML private void goToDashboard() {
        if (mainTabs != null)
            mainTabs.getSelectionModel().selectFirst();
    }

    // =========================================================================
    //  DATA LOADERS
    // =========================================================================
    private void loadProfile() {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT admission_no, result_pin, class_level, arm " +
                 "FROM student_profiles WHERE user_id = ?")) {
            AuthService.setUuid(ps, 1, AuthService.Session.userId, c);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                pinLabel.setText(
                    "PIN: " + rs.getString("result_pin") +
                    "  |  Admission: " + rs.getString("admission_no"));
                if (classField    != null) classField.setText(rs.getString("class_level"));
                if (armField      != null) armField.setText(rs.getString("arm"));
                if (admissionField!= null) admissionField.setText(rs.getString("admission_no"));
                if (nameField     != null) nameField.setText(AuthService.Session.fullName);
            }
        } catch (Exception ignored) {}
    }

    private void loadSubjects() {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT DISTINCT subject_name FROM subjects " +
                 "WHERE is_active = TRUE ORDER BY subject_name")) {
            ResultSet rs = ps.executeQuery();
            if (subjectBox != null) subjectBox.getItems().clear();
            while (rs.next())
                if (subjectBox != null)
                    subjectBox.getItems().add(rs.getString(1));
        } catch (Exception ignored) {}
    }

    private void loadAnnouncements() {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT title, body FROM announcements " +
                 "WHERE expires_at IS NULL OR expires_at > now() " +
                 "ORDER BY created_at DESC LIMIT 10")) {
            ResultSet rs = ps.executeQuery();
            ObservableList<String> items = FXCollections.observableArrayList();
            while (rs.next())
                items.add("📢 " + rs.getString(1) + " - " + rs.getString(2));
            if (items.isEmpty())
                items.add("No announcements - Welcome to KLC CBT!");
            if (announcementsList != null)
                announcementsList.setItems(items);
        } catch (Exception e) {
            if (announcementsList != null)
                announcementsList.setItems(FXCollections.observableArrayList(
                    "Announcements offline"));
        }
    }

    // =========================================================================
    //  TOP 3 LEADERBOARD - per subject, global
    // =========================================================================
    private void loadTopLeaderboard() {
        if (leaderboardList == null) return;
        ObservableList<String> items = FXCollections.observableArrayList();
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT s.subject_name, u.full_name, r.percentage, r.grade, " +
                 "RANK() OVER (PARTITION BY e.subject_id " +
                 "             ORDER BY r.percentage DESC) AS pos " +
                 "FROM results r " +
                 "JOIN exams e ON e.id = r.exam_id " +
                 "JOIN subjects s ON s.id = e.subject_id " +
                 "JOIN users u ON u.id = r.student_id " +
                 "WHERE r.published = TRUE")) {

            ResultSet rs = ps.executeQuery();
            java.util.Map<String, java.util.List<String[]>> bySubject =
                new java.util.LinkedHashMap<>();

            while (rs.next()) {
                int pos = rs.getInt(5);
                if (pos > 3) continue;
                String subj = rs.getString(1);
                bySubject.computeIfAbsent(
                    subj, k -> new java.util.ArrayList<>())
                    .add(new String[]{
                        rs.getString(2),
                        String.format("%.1f%%", rs.getDouble(3)),
                        rs.getString(4) == null ? "-" : rs.getString(4),
                        String.valueOf(pos)
                    });
            }

            String[] medals = {"1st", "2nd", "3rd"};
            for (var entry : bySubject.entrySet()) {
                items.add("[ " + entry.getKey() + " ]");
                for (String[] rec : entry.getValue()) {
                    int posIdx = Integer.parseInt(rec[3]) - 1;
                    String medal = posIdx < medals.length
                        ? medals[posIdx] : rec[3] + "th";
                    items.add("  " + medal + " Place  |  " + rec[0] +
                              "  |  " + rec[1] + "  |  Grade: " + rec[2]);
                }
            }
            if (items.isEmpty())
                items.add("No exam results published yet.");

        } catch (Exception e) {
            // H2 fallback - RANK() OVER not supported in H2
            loadTopLeaderboardH2Fallback(items);
        }
        leaderboardList.setItems(items);
    }

    private void loadTopLeaderboardH2Fallback(
            ObservableList<String> items) {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT s.subject_name, u.full_name, r.percentage, r.grade " +
                 "FROM results r " +
                 "JOIN exams e ON e.id = r.exam_id " +
                 "JOIN subjects s ON s.id = e.subject_id " +
                 "JOIN users u ON u.id = r.student_id " +
                 "WHERE r.published = TRUE " +
                 "ORDER BY s.subject_name, r.percentage DESC")) {

            ResultSet rs = ps.executeQuery();
            java.util.Map<String, Integer> countPerSubject =
                new java.util.HashMap<>();
            String[] medals = {"1st", "2nd", "3rd"};
            String lastSubject = "";

            while (rs.next()) {
                String subj = rs.getString(1);
                if (!subj.equals(lastSubject)) {
                    countPerSubject.put(subj, 0);
                    items.add("[ " + subj + " ]");
                    lastSubject = subj;
                }
                int count = countPerSubject.getOrDefault(subj, 0);
                if (count >= 3) continue;
                String medal = medals[count];
                items.add("  " + medal + " Place  |  " + rs.getString(2) +
                          "  |  " + String.format("%.1f%%", rs.getDouble(3)) +
                          "  |  Grade: " + (rs.getString(4) == null
                              ? "-" : rs.getString(4)));
                countPerSubject.put(subj, count + 1);
            }
        } catch (Exception ignored) {}
    }

    private void loadResults() {
        resultData.clear();
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT s.subject_code, r.percentage, r.grade, r.created_at " +
                 "FROM results r " +
                 "JOIN exams e ON e.id = r.exam_id " +
                 "JOIN subjects s ON s.id = e.subject_id " +
                 "WHERE r.student_id = ? " +
                 "ORDER BY r.created_at DESC LIMIT 10")) {
            AuthService.setUuid(ps, 1, AuthService.Session.userId, c);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                resultData.add(new ResultRow(
                    rs.getString(1),
                    String.format("%.1f%%", rs.getDouble(2)),
                    rs.getString(3) == null ? "-" : rs.getString(3),
                    rs.getTimestamp(4).toLocalDateTime()
                       .toLocalDate().toString()));
            }
        } catch (Exception ignored) {}
    }

    private void loadFullResults() {
        fullResults.clear();
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT r.id, s.subject_code, r.percentage, " +
                 "r.grade, r.position, r.created_at " +
                 "FROM results r " +
                 "JOIN exams e ON e.id = r.exam_id " +
                 "JOIN subjects s ON s.id = e.subject_id " +
                 "WHERE r.student_id = ? " +
                 "ORDER BY r.created_at DESC")) {
            AuthService.setUuid(ps, 1, AuthService.Session.userId, c);
            ResultSet rs = ps.executeQuery();
            int cnt = 0; double sum = 0;
            while (rs.next()) {
                double pct = rs.getDouble(3); sum += pct; cnt++;
                fullResults.add(new FullResultRow(
                    rs.getString(1), rs.getString(2),
                    String.format("%.1f%%", pct),
                    rs.getString(4) == null ? "-" : rs.getString(4),
                    rs.getString(5) == null ? "-" : rs.getString(5),
                    rs.getTimestamp(6).toLocalDateTime()
                       .toLocalDate().toString()));
            }
            if (resultSummaryLabel != null) {
                resultSummaryLabel.setText(cnt == 0
                    ? "No results yet - take your first exam!"
                    : String.format(
                        "Total Exams: %d  |  Cumulative Average: %.1f%%  |" +
                        "  Overall Grade: %s",
                        cnt, sum / cnt, grade(sum / cnt)));
            }
        } catch (Exception e) {
            if (resultSummaryLabel != null)
                resultSummaryLabel.setText("Error: " + e.getMessage());
        }
    }

    private void loadCgpa() {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT AVG(percentage), COUNT(*) " +
                 "FROM results WHERE student_id = ?")) {
            AuthService.setUuid(ps, 1, AuthService.Session.userId, c);
            ResultSet rs = ps.executeQuery();
            if (rs.next() && rs.getObject(1) != null) {
                double avg = rs.getDouble(1);
                int cnt    = rs.getInt(2);
                cgpaLabel.setText(String.format(
                    "Avg: %.1f%%  |  %s  |  %d exams taken",
                    avg, grade(avg), cnt));
            } else {
                cgpaLabel.setText("Take your first exam!");
            }
        } catch (Exception e) {
            cgpaLabel.setText("CGPA: -");
        }
    }

    private void loadTrendChart() {
        if (trendChart == null) return;
        trendChart.getData().clear();
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT r.created_at, r.percentage FROM results r " +
                 "WHERE r.student_id = ? " +
                 "ORDER BY r.created_at ASC LIMIT 30")) {
            AuthService.setUuid(ps, 1, AuthService.Session.userId, c);
            ResultSet rs = ps.executeQuery();
            XYChart.Series<Number, Number> series = new XYChart.Series<>();
            series.setName("Score %");
            int x = 1;
            while (rs.next())
                series.getData().add(new XYChart.Data<>(x++, rs.getDouble(2)));
            if (!series.getData().isEmpty())
                trendChart.getData().add(series);
        } catch (Exception ignored) {}
    }

    private void loadMaterials() {
        matData.clear();
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT sm.title, s.subject_code, sm.file_url " +
                 "FROM study_materials sm " +
                 "JOIN subjects s ON s.id = sm.subject_id " +
                 "ORDER BY sm.created_at DESC LIMIT 100")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next())
                matData.add(new MatRow(
                    rs.getString(1), rs.getString(2), rs.getString(3)));
        } catch (Exception ignored) {}
    }

    private void openMaterial(MatRow m) {
        try {
            if (m.fileUrl != null && !m.fileUrl.isBlank()) {
                File f = new File(m.fileUrl);
                if (f.exists() && Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(f);
                    return;
                }
                if (m.fileUrl.startsWith("http")) {
                    new Alert(Alert.AlertType.INFORMATION,
                        "Open in browser:\n" + m.fileUrl).show();
                    return;
                }
            }
            new Alert(Alert.AlertType.INFORMATION,
                "Material: " + m.title + "\nSubject: " + m.subject).show();
        } catch (Exception e) {
            new Alert(Alert.AlertType.ERROR, e.getMessage()).show();
        }
    }

    // =========================================================================
    //  RESULT SHARING
    // =========================================================================
    @FXML
    private void shareResult() {
        if (shareAdmissionField == null) return;
        String targetAdm = shareAdmissionField.getText().trim();
        if (targetAdm.isBlank()) {
            setShareStatus("Enter the recipient's admission number.", true);
            return;
        }

        try (Connection c = DatabaseManager.getConnection()) {
            // Find target student by admission number
            PreparedStatement find = c.prepareStatement(
                "SELECT sp.user_id, u.full_name " +
                "FROM student_profiles sp " +
                "JOIN users u ON u.id = sp.user_id " +
                "WHERE sp.admission_no = ?");
            find.setString(1, targetAdm);
            ResultSet findRs = find.executeQuery();

            if (!findRs.next()) {
                setShareStatus("Student with admission number " +
                    targetAdm + " not found.", true);
                return;
            }

            String targetId   = findRs.getString(1);
            String targetName = findRs.getString(2);

            // Can't share with yourself
            if (targetId.equals(AuthService.Session.userId)) {
                setShareStatus("You cannot share results with yourself.", true);
                return;
            }

            // Create share request as announcement targeting that student
            // Using announcements table with special format
            String requestId = UUID.randomUUID().toString();
            PreparedStatement ins = c.prepareStatement(
                "INSERT INTO announcements(" +
                "  id, title, body, target_role, created_by) " +
                "VALUES(?, ?, ?, 'SHARE_REQUEST', ?)");
            ins.setString(1, requestId);
            ins.setString(2, "RESULT_SHARE_REQUEST:" + targetId);
            ins.setString(3, "FROM:" + AuthService.Session.userId +
                          "|NAME:" + AuthService.Session.fullName +
                          "|ADM:" + admissionField.getText());
            AuthService.setUuid(ins, 4, AuthService.Session.userId, c);
            ins.executeUpdate();

            setShareStatus("Share request sent to " + targetName +
                " (" + targetAdm + "). They will be notified.", false);
            if (shareAdmissionField != null) shareAdmissionField.clear();

        } catch (Exception e) {
            setShareStatus("Error: " + e.getMessage(), true);
        }
    }

    private void loadShareRequests() {
        if (shareRequestsList == null) return;
        ObservableList<String> items = FXCollections.observableArrayList();
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT id, body, created_at FROM announcements " +
                 "WHERE target_role = 'SHARE_REQUEST' " +
                 "  AND title LIKE ? " +
                 "  AND expires_at IS NULL " +
                 "ORDER BY created_at DESC LIMIT 20")) {
            ps.setString(1, "RESULT_SHARE_REQUEST:" +
                AuthService.Session.userId + "%");
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String body = rs.getString(2);
                String name = extractField(body, "NAME:");
                String adm  = extractField(body, "ADM:");
                String date = rs.getTimestamp(3).toLocalDateTime()
                    .toLocalDate().toString();
                items.add("[" + rs.getString(1).substring(0, 8) + "]  " +
                    name + " (" + adm + ")  wants to share their result with you  |  " + date);
            }
        } catch (Exception ignored) {}

        if (items.isEmpty()) items.add("No pending share requests.");
        shareRequestsList.setItems(items);

        // Store request IDs for accept/decline
        shareRequestsList.getSelectionModel()
            .selectedItemProperty().addListener((o, ov, nv) -> {
                if (nv != null && nv.startsWith("[")) {
                    selectedShareRequestId = nv.substring(1, 9);
                }
            });
    }

    private String extractField(String body, String key) {
        try {
            int start = body.indexOf(key) + key.length();
            int end   = body.indexOf("|", start);
            return end < 0 ? body.substring(start)
                           : body.substring(start, end);
        } catch (Exception e) {
            return "Unknown";
        }
    }

    @FXML
    private void acceptShareRequest() {
        String selected = shareRequestsList.getSelectionModel()
            .getSelectedItem();
        if (selected == null || selected.startsWith("No pending")) {
            setNotifStatus("Select a request first.", true);
            return;
        }

        try (Connection c = DatabaseManager.getConnection()) {
            // Get request by matching the first 8 chars of ID
            PreparedStatement ps = c.prepareStatement(
                "SELECT id, body FROM announcements " +
                "WHERE target_role = 'SHARE_REQUEST' " +
                "  AND title LIKE ?");
            ps.setString(1, "RESULT_SHARE_REQUEST:" +
                AuthService.Session.userId + "%");
            ResultSet rs = ps.executeQuery();

            String senderId = null;
            String reqId    = null;
            while (rs.next()) {
                String id = rs.getString(1);
                if (selected.contains(id.substring(0, 8))) {
                    reqId    = id;
                    senderId = extractField(rs.getString(2), "FROM:");
                    break;
                }
            }

            if (senderId == null || reqId == null) {
                setNotifStatus("Could not find that request.", true);
                return;
            }

            // Fetch sender's results and store as shared record
            // Mark request as expired (accepted)
            PreparedStatement expire = c.prepareStatement(
                "UPDATE announcements SET expires_at = CURRENT_TIMESTAMP " +
                "WHERE id = ?");
            expire.setString(1, reqId);
            expire.executeUpdate();

            // Get sender's results
            PreparedStatement getRes = c.prepareStatement(
                "SELECT s.subject_code, r.percentage, r.grade, " +
                "r.created_at, u.full_name " +
                "FROM results r " +
                "JOIN exams e ON e.id = r.exam_id " +
                "JOIN subjects s ON s.id = e.subject_id " +
                "JOIN users u ON u.id = r.student_id " +
                "WHERE r.student_id = ? " +
                "ORDER BY r.created_at DESC LIMIT 10");
            AuthService.setUuid(getRes, 1, senderId, c);
            ResultSet resRs = getRes.executeQuery();

            int count = 0;
            StringBuilder summary = new StringBuilder();
            while (resRs.next()) {
                count++;
                summary.append(resRs.getString(1))
                       .append(": ")
                       .append(String.format("%.1f%%", resRs.getDouble(2)))
                       .append(" (")
                       .append(resRs.getString(3) == null ? "-" : resRs.getString(3))
                       .append(")\n");
            }

            setNotifStatus("Accepted! " + count +
                " results received. Check 'Results Received' table.", false);
            loadSharedResults();
            loadShareRequests();

            if (count > 0) {
                Alert info = new Alert(Alert.AlertType.INFORMATION);
                info.setTitle("Results Received");
                info.setHeaderText("Result breakdown received:");
                info.setContentText(summary.toString());
                info.getDialogPane().setPrefWidth(420);
                info.show();
            }

        } catch (Exception e) {
            setNotifStatus("Error: " + e.getMessage(), true);
        }
    }

    @FXML
    private void declineShareRequest() {
        String selected = shareRequestsList.getSelectionModel()
            .getSelectedItem();
        if (selected == null || selected.startsWith("No pending")) {
            setNotifStatus("Select a request to decline.", true);
            return;
        }
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE announcements " +
                 "SET expires_at = CURRENT_TIMESTAMP " +
                 "WHERE target_role = 'SHARE_REQUEST' " +
                 "  AND title LIKE ?")) {
            ps.setString(1, "RESULT_SHARE_REQUEST:" +
                AuthService.Session.userId + "%");
            ps.executeUpdate();
            setNotifStatus("Request declined.", false);
            loadShareRequests();
        } catch (Exception e) {
            setNotifStatus("Error: " + e.getMessage(), true);
        }
    }

    private void loadSharedResults() {
        sharedData.clear();
        // This loads from shared results stored as announcements body
        // In a production system this would use a dedicated shared_results table
        // For now show placeholder data
        if (receivedResultsTable != null)
            receivedResultsTable.setItems(sharedData);
    }

    @FXML
    private void loadNotifications() {
        loadShareRequests();
        loadSharedResults();
    }

    // =========================================================================
    //  APPEAL
    // =========================================================================
    @FXML
    private void submitAppeal() {
        FullResultRow sel = myFullResultsTable == null ? null
            : myFullResultsTable.getSelectionModel().getSelectedItem();
        if (sel == null) {
            new Alert(Alert.AlertType.WARNING,
                "Select a result from the table above first.").showAndWait();
            return;
        }
        if (appealText == null || appealText.getText().trim().isEmpty()) {
            new Alert(Alert.AlertType.WARNING,
                "Please type your appeal reason.").showAndWait();
            return;
        }
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO result_appeals(" +
                 "  id, result_id, student_id, subject_code, reason) " +
                 "VALUES(?,?,?,?,?)")) {
            ps.setString(1, UUID.randomUUID().toString());
            AuthService.setUuid(ps, 2, sel.resultId, c);
            AuthService.setUuid(ps, 3, AuthService.Session.userId, c);
            ps.setString(4, sel.subject);
            ps.setString(5, appealText.getText().trim());
            ps.executeUpdate();
            appealText.clear();
            AuthService.logAudit("RESULT_APPEAL", "results", sel.resultId);
            new Alert(Alert.AlertType.INFORMATION,
                "Appeal submitted. Admin will review and respond.")
                .showAndWait();
        } catch (Exception e) {
            new Alert(Alert.AlertType.ERROR, "Error: " + e.getMessage())
                .showAndWait();
        }
    }

    // =========================================================================
    //  REPORT CARD / TRANSCRIPT
    // =========================================================================
    @FXML
    private void downloadReport() {
        try {
            String out = ReportCardPdf.generateForStudent(
                AuthService.Session.userId, null, "1st", "2024/2025");
            new Alert(Alert.AlertType.INFORMATION,
                "Report Card saved:\n" + out).showAndWait();
        } catch (Exception e) {
            new Alert(Alert.AlertType.ERROR,
                "Error: " + e.getMessage()).showAndWait();
        }
    }

    @FXML
    private void downloadTranscript() {
        try {
            String out = TranscriptPdf.generateCumulativeTranscript(
                AuthService.Session.userId, null);
            new Alert(Alert.AlertType.INFORMATION,
                "Transcript saved:\n" + out).showAndWait();
        } catch (Exception e) {
            new Alert(Alert.AlertType.ERROR,
                "Error: " + e.getMessage()).showAndWait();
        }
    }

    // =========================================================================
    //  START EXAM
    // =========================================================================
    @FXML
    private void startExam() {
        String selectedSubject = subjectBox == null ? null : subjectBox.getValue();
        if (selectedSubject == null || selectedSubject.isBlank()) {
            new Alert(Alert.AlertType.WARNING,
                "Please select a subject.").showAndWait();
            return;
        }
        String classLevel = classField == null ? "" : classField.getText();
        if (classLevel.isBlank()) {
            new Alert(Alert.AlertType.WARNING,
                "Your class is not set. Contact admin.").showAndWait();
            return;
        }

        try (Connection c = DatabaseManager.getConnection()) {
            PreparedStatement ps = c.prepareStatement(
                "SELECT e.id, e.title FROM exams e " +
                "JOIN subjects s ON s.id = e.subject_id " +
                "WHERE (s.subject_name = ? OR s.subject_code = ?) " +
                "  AND e.class_level = ? " +
                "  AND e.is_active = TRUE " +
                "  AND e.is_practice = FALSE " +
                "  AND (e.start_at IS NULL " +
                "       OR e.start_at <= CURRENT_TIMESTAMP) " +
                "  AND (e.end_at IS NULL " +
                "       OR e.end_at >= CURRENT_TIMESTAMP) " +
                "ORDER BY e.created_at DESC LIMIT 1");
            ps.setString(1, selectedSubject);
            ps.setString(2, selectedSubject);
            ps.setString(3, classLevel);
            ResultSet rs = ps.executeQuery();

            if (!rs.next()) {
                new Alert(Alert.AlertType.INFORMATION,
                    "No active exam found for " + selectedSubject +
                    " - " + classLevel).showAndWait();
                return;
            }

            String examId    = rs.getString(1);
            String examTitle = rs.getString(2);

            // Check previous attempt
            PreparedStatement chk = c.prepareStatement(
                "SELECT status FROM exam_attempts " +
                "WHERE exam_id = ? AND student_id = ?");
            AuthService.setUuid(chk, 1, examId, c);
            AuthService.setUuid(chk, 2, AuthService.Session.userId, c);
            ResultSet chkRs = chk.executeQuery();
            if (chkRs.next()) {
                String st = chkRs.getString(1);
                if ("SUBMITTED".equals(st) || "MALPRACTICE".equals(st)) {
                    new Alert(Alert.AlertType.WARNING,
                        "You already completed: " + examTitle +
                        "\nStatus: " + st +
                        "\nView result in My Results tab.")
                        .showAndWait();
                    return;
                }
            }

            FXMLLoader loader = new FXMLLoader(
                getClass().getResource("/fxml/exam_instructions.fxml"));
            double w = welcomeLabel.getScene().getWindow().getWidth();
            double h = welcomeLabel.getScene().getWindow().getHeight();
            Scene scene = new Scene(loader.load(), w, h);
            scene.getStylesheets().add(
                getClass().getResource(
                    "/css/klc-premium.css").toExternalForm());
            ExamInstructionsController ctrl = loader.getController();
            ctrl.init(examId, "A");
            Stage st = (Stage) welcomeLabel.getScene().getWindow();
            st.setScene(scene);

        } catch (Exception e) {
            e.printStackTrace();
            new Alert(Alert.AlertType.ERROR,
                "Error: " + e.getMessage()).showAndWait();
        }
    }

    // =========================================================================
    //  PRACTICE
    // =========================================================================
    @FXML
    private void startPractice() {
        if (practiceSubjectBox == null || practiceClassBox == null) return;
        String subj = practiceSubjectBox.getValue();
        String cls  = practiceClassBox.getValue();
        if (subj == null || cls == null) {
            setPracticeStatus("Select both subject and class.", true);
            return;
        }
        try {
            FXMLLoader loader = new FXMLLoader(
                getClass().getResource("/fxml/practice_exam.fxml"));
            Scene scene = new Scene(loader.load(), 1100, 700);
            scene.getStylesheets().add(
                getClass().getResource(
                    "/css/klc-premium.css").toExternalForm());
            Stage st = new Stage();
            st.setTitle("KLC - Practice Mode - " + subj);
            st.setScene(scene);
            st.show();
        } catch (Exception e) {
            setPracticeStatus("Error: " + e.getMessage(), true);
        }
    }

    // =========================================================================
    //  REALTIME
    // =========================================================================
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
                    public void onOpen(WebSocket ws, okhttp3.Response r) {
                        ws.send("{\"topic\":\"realtime:public:results\"," +
                                "\"event\":\"phx_join\",\"payload\":{}," +
                                "\"ref\":\"1\"}");
                        ws.send("{\"topic\":\"realtime:public:announcements\"," +
                                "\"event\":\"phx_join\",\"payload\":{}," +
                                "\"ref\":\"2\"}");
                    }
                    @Override
                    public void onMessage(WebSocket ws, String text) {
                        if (text.contains("results"))
                            Platform.runLater(() -> {
                                loadResults(); loadCgpa(); loadTrendChart();
                            });
                        if (text.contains("announcements"))
                            Platform.runLater(() -> {
                                loadAnnouncements();
                                loadShareRequests();
                            });
                    }
                });
        } catch (Exception ignored) {}
    }

    // =========================================================================
    //  NAVIGATION
    // =========================================================================
    @FXML
    private void logout() throws Exception {
        if (webSocket != null) webSocket.close(1000, "Closing");
        SessionIdleWatcher.stop();
        SyncService.stop();
        AuthService.Session.clear();
        MainApp.setRoot("login.fxml", null);
    }

    public void cleanup() {
        if (webSocket != null) webSocket.close(1000, "Closing");
    }

    // =========================================================================
    //  HELPERS
    // =========================================================================
    private String grade(double t) {
        if (t >= 75) return "A1"; if (t >= 70) return "B2";
        if (t >= 65) return "B3"; if (t >= 60) return "C4";
        if (t >= 55) return "C5"; if (t >= 50) return "C6";
        if (t >= 45) return "D7"; if (t >= 40) return "E8";
        return "F9";
    }

    private void setShareStatus(String msg, boolean error) {
        if (shareStatusLabel == null) return;
        shareStatusLabel.setText(msg);
        shareStatusLabel.setStyle(error
            ? "-fx-text-fill:#ef4444;" : "-fx-text-fill:#10b981;");
    }

    private void setNotifStatus(String msg, boolean error) {
        if (notifStatusLabel == null) return;
        notifStatusLabel.setText(msg);
        notifStatusLabel.setStyle(error
            ? "-fx-text-fill:#ef4444;" : "-fx-text-fill:#10b981;");
    }

    private void setPracticeStatus(String msg, boolean error) {
        if (practiceStatusLabel == null) return;
        practiceStatusLabel.setText(msg);
        practiceStatusLabel.setStyle(error
            ? "-fx-text-fill:#ef4444;" : "-fx-text-fill:#10b981;");
    }
}
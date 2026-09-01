package com.femzyk.klc.student;

import com.femzyk.klc.MainApp;
import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.auth.SessionIdleWatcher;
import com.femzyk.klc.db.DatabaseManager;
import com.femzyk.klc.util.ReportCardPdf;
import com.femzyk.klc.util.SyncService;
import com.femzyk.klc.util.TranscriptPdf;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.awt.Desktop;
import java.io.File;
import java.sql.*;
import java.util.UUID;

/**
 * StudentDashboardController - KLC CBT Suite v1.0
 *
 * RULE 11 FIXES vs previous version:
 * 1. Rule 3/6: removed private OkHttpClient + WebSocket + hardcoded anon
 *    key (leak source). Live updates now via secure 30s Timeline
 *    auto-refresh over the pooled connection; self-stops on scene exit.
 * 2. Rule 5: setUuid for ALL UUID binds (share insert, appeal insert,
 *    request expiry).
 * 3. BUG: declineShareRequest declined ALL requests - now declines only
 *    the selected one.
 * 4. SECURITY: students no longer see PENDING teacher announcements or
 *    SHARE_REQUEST rows in the notice board.
 * 5. G3: trend chart upgraded - one line PER SUBJECT.
 * ALL features preserved: exam start, practice, results, sharing,
 * appeals, leaderboard, e-library, report/transcript downloads.
 */
public class StudentDashboardController {

    private static final int REFRESH_SECONDS = 30;

    @FXML private Label welcomeLabel, pinLabel, cgpaLabel;
    @FXML private TabPane mainTabs;

    @FXML private ComboBox<String>  subjectBox;
    @FXML private TextField         classField, armField, nameField, admissionField;
    @FXML private ListView<String>  announcementsList;
    @FXML private ListView<String>  leaderboardList;
    @FXML private TableView<ResultRow> resultsTable;
    @FXML private TableColumn<ResultRow, String> colRSub, colRScore, colRGrade, colRDate;
    @FXML private LineChart<Number, Number> trendChart;

    @FXML private TableView<FullResultRow> myFullResultsTable;
    @FXML private TableColumn<FullResultRow, String> colMySubject, colMyScore,
                                                     colMyGrade, colMyPosition, colMyDate;
    @FXML private Label      resultSummaryLabel;
    @FXML private TextArea   appealText;
    @FXML private TextField  shareAdmissionField;
    @FXML private Label      shareStatusLabel;

    @FXML private ListView<String>   shareRequestsList;
    @FXML private TableView<SharedResultRow> receivedResultsTable;
    @FXML private TableColumn<SharedResultRow, String> colRcvFrom, colRcvSubject,
                                                       colRcvScore, colRcvGrade, colRcvDate;
    @FXML private Label notifStatusLabel;

    @FXML private ComboBox<String> practiceSubjectBox, practiceClassBox;
    @FXML private Label practiceStatusLabel;

    @FXML private TableView<MatRow>           materialsTable;
    @FXML private TableColumn<MatRow, String> colMatTitle, colMatSub;

    private Timeline autoRefresh;

    public static class MatRow {
        public String title, subject, fileUrl;
        MatRow(String t, String s, String f) { title = t; subject = s; fileUrl = f; }
        public String getTitle()   { return title; }
        public String getSubject() { return subject; }
    }
    public static class ResultRow {
        String subject, score, grade, date;
        ResultRow(String s, String sc, String g, String d) {
            subject = s; score = sc; grade = g; date = d; }
        public String getSubject() { return subject; }
        public String getScore()   { return score; }
        public String getGrade()   { return grade; }
        public String getDate()    { return date; }
    }
    public static class FullResultRow {
        String resultId, subject, score, grade, position, date;
        FullResultRow(String rid, String s, String sc, String g, String pos, String d) {
            resultId = rid; subject = s; score = sc; grade = g; position = pos; date = d; }
        public String getSubject()  { return subject; }
        public String getScore()    { return score; }
        public String getGrade()    { return grade; }
        public String getPosition() { return position == null ? "-" : position; }
        public String getDate()     { return date; }
    }
    public static class SharedResultRow {
        String from, subject, score, grade, date;
        SharedResultRow(String f, String s, String sc, String g, String d) {
            from = f; subject = s; score = sc; grade = g; date = d; }
        public String getFrom()    { return from; }
        public String getSubject() { return subject; }
        public String getScore()   { return score; }
        public String getGrade()   { return grade; }
        public String getDate()    { return date; }
    }

    ObservableList<MatRow>          matData     = FXCollections.observableArrayList();
    ObservableList<ResultRow>       resultData  = FXCollections.observableArrayList();
    ObservableList<FullResultRow>   fullResults = FXCollections.observableArrayList();
    ObservableList<SharedResultRow> sharedData  = FXCollections.observableArrayList();

    @FXML
    public void initialize() {
        welcomeLabel.setText("Welcome, " + AuthService.Session.fullName);

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

        startAutoRefresh();

        welcomeLabel.sceneProperty().addListener((o, ov, nv) -> {
            if (nv != null) SessionIdleWatcher.start(nv, () -> {});
            else stopAutoRefresh();
        });
    }

    // ── Secure live updates: 30s poll, self-stopping ─────────────────────
    private void startAutoRefresh() {
        stopAutoRefresh();
        autoRefresh = new Timeline(new KeyFrame(
            Duration.seconds(REFRESH_SECONDS), e -> {
                if (welcomeLabel == null || welcomeLabel.getScene() == null) {
                    stopAutoRefresh(); return;
                }
                if (MainApp.examInProgress) return; // never poll during exams
                loadAnnouncements();
                loadResults();
                loadCgpa();
                loadShareRequests();
            }));
        autoRefresh.setCycleCount(Timeline.INDEFINITE);
        autoRefresh.play();
    }

    private void stopAutoRefresh() {
        if (autoRefresh != null) { autoRefresh.stop(); autoRefresh = null; }
    }

    private void setupResultsTableCols() {
        colRSub.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getSubject()));
        colRScore.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getScore()));
        colRGrade.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getGrade()));
        colRDate.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getDate()));
        resultsTable.setItems(resultData);
    }
    private void setupFullResultsTableCols() {
        colMySubject.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getSubject()));
        colMyScore.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getScore()));
        colMyGrade.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getGrade()));
        colMyPosition.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getPosition()));
        colMyDate.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getDate()));
        myFullResultsTable.setItems(fullResults);
    }
    private void setupMaterialsTable() {
        colMatTitle.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getTitle()));
        colMatSub.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getSubject()));
        materialsTable.setItems(matData);
        materialsTable.setRowFactory(tv -> {
            TableRow<MatRow> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) openMaterial(row.getItem());
            });
            return row;
        });
    }
    private void setupSharedResultsTable() {
        colRcvFrom.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getFrom()));
        colRcvSubject.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getSubject()));
        colRcvScore.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getScore()));
        colRcvGrade.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getGrade()));
        colRcvDate.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getDate()));
        receivedResultsTable.setItems(sharedData);
    }

    private void setupPracticeTab() {
        if (practiceSubjectBox != null) {
            try (Connection c = DatabaseManager.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                     "SELECT DISTINCT subject_name FROM subjects " +
                     "WHERE is_active = TRUE ORDER BY subject_name")) {
                ResultSet rs = ps.executeQuery();
                while (rs.next()) practiceSubjectBox.getItems().add(rs.getString(1));
            } catch (Exception ignored) {}
        }
        if (practiceClassBox != null)
            practiceClassBox.getItems().addAll("JSS1","JSS2","JSS3","SS1","SS2","SS3");
    }

    @FXML private void onDashboardTab() {
        loadAnnouncements(); loadTopLeaderboard(); loadResults(); loadCgpa(); loadTrendChart();
    }
    @FXML private void onResultsTab()       { loadFullResults(); loadSharedResults(); }
    @FXML private void onNotificationsTab() { loadShareRequests(); loadSharedResults(); }
    @FXML private void onPracticeTab()      { }
    @FXML private void onLibraryTab()       { loadMaterials(); }
    @FXML private void goToDashboard() {
        if (mainTabs != null) mainTabs.getSelectionModel().selectFirst();
    }

    private void loadProfile() {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT admission_no, result_pin, class_level, arm " +
                 "FROM student_profiles WHERE user_id = ?")) {
            AuthService.setUuid(ps, 1, AuthService.Session.userId, c);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                pinLabel.setText("PIN: " + rs.getString("result_pin") +
                    "  |  Admission: " + rs.getString("admission_no"));
                if (classField != null) classField.setText(rs.getString("class_level"));
                if (armField != null) armField.setText(rs.getString("arm"));
                if (admissionField != null) admissionField.setText(rs.getString("admission_no"));
                if (nameField != null) nameField.setText(AuthService.Session.fullName);
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
                if (subjectBox != null) subjectBox.getItems().add(rs.getString(1));
        } catch (Exception ignored) {}
    }

    /** FIX: hides PENDING announcements and SHARE_REQUEST rows. */
    private void loadAnnouncements() {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT title, body FROM announcements " +
                 "WHERE (target_role = 'ALL' OR target_role = 'STUDENT') " +
                 "  AND (status = 'APPROVED' OR status IS NULL) " +
                 "  AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP) " +
                 "ORDER BY created_at DESC LIMIT 10")) {
            ResultSet rs = ps.executeQuery();
            ObservableList<String> items = FXCollections.observableArrayList();
            while (rs.next())
                items.add(rs.getString(1) + " - " + rs.getString(2));
            if (items.isEmpty()) items.add("No announcements - Welcome to KLC CBT!");
            if (announcementsList != null) announcementsList.setItems(items);
        } catch (Exception e) {
            if (announcementsList != null)
                announcementsList.setItems(FXCollections.observableArrayList(
                    "Announcements offline"));
        }
    }

    private void loadTopLeaderboard() {
        if (leaderboardList == null) return;
        ObservableList<String> items = FXCollections.observableArrayList();
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
            java.util.Map<String, Integer> countPerSubject = new java.util.HashMap<>();
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
                items.add("  " + medals[count] + " Place  |  " + rs.getString(2) +
                    "  |  " + String.format("%.1f%%", rs.getDouble(3)) +
                    "  |  Grade: " + (rs.getString(4) == null ? "-" : rs.getString(4)));
                countPerSubject.put(subj, count + 1);
            }
            if (items.isEmpty()) items.add("No exam results published yet.");
        } catch (Exception ignored) {}
        leaderboardList.setItems(items);
    }

    private void loadResults() {
        resultData.clear();
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT s.subject_code, r.percentage, r.grade, r.created_at " +
                 "FROM results r " +
                 "JOIN exams e ON e.id = r.exam_id " +
                 "JOIN subjects s ON s.id = e.subject_id " +
                 "WHERE r.student_id = ? ORDER BY r.created_at DESC LIMIT 10")) {
            AuthService.setUuid(ps, 1, AuthService.Session.userId, c);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                resultData.add(new ResultRow(
                    rs.getString(1),
                    String.format("%.1f%%", rs.getDouble(2)),
                    rs.getString(3) == null ? "-" : rs.getString(3),
                    rs.getTimestamp(4).toLocalDateTime().toLocalDate().toString()));
            }
        } catch (Exception ignored) {}
    }

    private void loadFullResults() {
        fullResults.clear();
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT r.id, s.subject_code, r.percentage, r.grade, " +
                 "r.position, r.created_at FROM results r " +
                 "JOIN exams e ON e.id = r.exam_id " +
                 "JOIN subjects s ON s.id = e.subject_id " +
                 "WHERE r.student_id = ? ORDER BY r.created_at DESC")) {
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
                    rs.getTimestamp(6).toLocalDateTime().toLocalDate().toString()));
            }
            if (resultSummaryLabel != null) {
                resultSummaryLabel.setText(cnt == 0
                    ? "No results yet - take your first exam!"
                    : String.format("Total Exams: %d  |  Cumulative Average: %.1f%%" +
                        "  |  Overall Grade: %s", cnt, sum / cnt, grade(sum / cnt)));
            }
        } catch (Exception e) {
            if (resultSummaryLabel != null)
                resultSummaryLabel.setText("Error: " + e.getMessage());
        }
    }

    private void loadCgpa() {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT AVG(percentage), COUNT(*) FROM results WHERE student_id = ?")) {
            AuthService.setUuid(ps, 1, AuthService.Session.userId, c);
            ResultSet rs = ps.executeQuery();
            if (rs.next() && rs.getObject(1) != null) {
                double avg = rs.getDouble(1);
                cgpaLabel.setText(String.format(
                    "Avg: %.1f%%  |  %s  |  %d exams taken",
                    avg, grade(avg), rs.getInt(2)));
            } else cgpaLabel.setText("Take your first exam!");
        } catch (Exception e) { cgpaLabel.setText("CGPA: -"); }
    }

    /** G3 UPGRADE: one trend line PER SUBJECT. */
    private void loadTrendChart() {
        if (trendChart == null) return;
        trendChart.getData().clear();
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT s.subject_code, r.percentage FROM results r " +
                 "JOIN exams e ON e.id = r.exam_id " +
                 "JOIN subjects s ON s.id = e.subject_id " +
                 "WHERE r.student_id = ? ORDER BY r.created_at ASC LIMIT 60")) {
            AuthService.setUuid(ps, 1, AuthService.Session.userId, c);
            ResultSet rs = ps.executeQuery();
            java.util.Map<String, XYChart.Series<Number, Number>> bySubject =
                new java.util.LinkedHashMap<>();
            java.util.Map<String, Integer> xCounter = new java.util.HashMap<>();
            while (rs.next()) {
                String subj = rs.getString(1);
                XYChart.Series<Number, Number> series =
                    bySubject.computeIfAbsent(subj, k -> {
                        XYChart.Series<Number, Number> sN = new XYChart.Series<>();
                        sN.setName(k);
                        return sN;
                    });
                int x = xCounter.merge(subj, 1, Integer::sum);
                series.getData().add(new XYChart.Data<>(x, rs.getDouble(2)));
            }
            for (XYChart.Series<Number, Number> s : bySubject.values())
                trendChart.getData().add(s);
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
                matData.add(new MatRow(rs.getString(1), rs.getString(2), rs.getString(3)));
        } catch (Exception ignored) {}
    }

    private void openMaterial(MatRow m) {
        try {
            if (m.fileUrl != null && !m.fileUrl.isBlank()) {
                File f = new File(m.fileUrl);
                if (f.exists() && Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(f); return;
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

    // ── RESULT SHARING (setUuid fixed, decline-selected fixed) ───────────
    @FXML
    private void shareResult() {
        if (shareAdmissionField == null) return;
        String targetAdm = shareAdmissionField.getText().trim();
        if (targetAdm.isBlank()) {
            setShareStatus("Enter the recipient's admission number.", true); return;
        }
        try (Connection c = DatabaseManager.getConnection()) {
            String targetId = null, targetName = null;
            try (PreparedStatement find = c.prepareStatement(
                    "SELECT sp.user_id, u.full_name FROM student_profiles sp " +
                    "JOIN users u ON u.id = sp.user_id WHERE sp.admission_no = ?")) {
                find.setString(1, targetAdm);
                ResultSet rs = find.executeQuery();
                if (rs.next()) { targetId = rs.getString(1); targetName = rs.getString(2); }
            }
            if (targetId == null) {
                setShareStatus("Student with admission number " + targetAdm +
                    " not found.", true);
                return;
            }
            if (targetId.equals(AuthService.Session.userId)) {
                setShareStatus("You cannot share results with yourself.", true); return;
            }
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO announcements(id, title, body, target_role, created_by) " +
                    "VALUES(?,?,?, 'SHARE_REQUEST', ?)")) {
                AuthService.setUuid(ins, 1, UUID.randomUUID().toString(), c);
                ins.setString(2, "RESULT_SHARE_REQUEST:" + targetId);
                ins.setString(3, "FROM:" + AuthService.Session.userId +
                    "|NAME:" + AuthService.Session.fullName +
                    "|ADM:" + (admissionField == null ? "" : admissionField.getText()));
                AuthService.setUuid(ins, 4, AuthService.Session.userId, c);
                ins.executeUpdate();
            }
            setShareStatus("Share request sent to " + targetName +
                " (" + targetAdm + ").", false);
            shareAdmissionField.clear();
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
                 "WHERE target_role = 'SHARE_REQUEST' AND title LIKE ? " +
                 "  AND expires_at IS NULL ORDER BY created_at DESC LIMIT 20")) {
            ps.setString(1, "RESULT_SHARE_REQUEST:" + AuthService.Session.userId + "%");
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String body = rs.getString(2);
                items.add("[" + rs.getString(1).substring(0, 8) + "]  " +
                    extractField(body, "NAME:") + " (" + extractField(body, "ADM:") +
                    ")  wants to share their result  |  " +
                    rs.getTimestamp(3).toLocalDateTime().toLocalDate());
            }
        } catch (Exception ignored) {}
        if (items.isEmpty()) items.add("No pending share requests.");
        shareRequestsList.setItems(items);
    }

    private String extractField(String body, String key) {
        try {
            int start = body.indexOf(key) + key.length();
            int end = body.indexOf("|", start);
            return end < 0 ? body.substring(start) : body.substring(start, end);
        } catch (Exception e) { return "Unknown"; }
    }

    /** Finds the full request id whose first 8 chars match the selection. */
    private String[] findSelectedRequest(Connection c, String selected)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, body FROM announcements " +
                "WHERE target_role = 'SHARE_REQUEST' AND title LIKE ? " +
                "  AND expires_at IS NULL")) {
            ps.setString(1, "RESULT_SHARE_REQUEST:" +
                AuthService.Session.userId + "%");
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String id = rs.getString(1);
                if (selected.contains(id.substring(0, 8)))
                    return new String[]{ id, rs.getString(2) };
            }
        }
        return null;
    }

    @FXML
    private void acceptShareRequest() {
        String selected = shareRequestsList.getSelectionModel().getSelectedItem();
        if (selected == null || selected.startsWith("No pending")) {
            setNotifStatus("Select a request first.", true); return;
        }
        try (Connection c = DatabaseManager.getConnection()) {
            String[] req = findSelectedRequest(c, selected);
            if (req == null) {
                setNotifStatus("Could not find that request.", true); return;
            }
            String reqId = req[0];
            String senderId = extractField(req[1], "FROM:");
            String senderName = extractField(req[1], "NAME:");

            try (PreparedStatement expire = c.prepareStatement(
                    "UPDATE announcements SET expires_at = CURRENT_TIMESTAMP WHERE id = ?")) {
                AuthService.setUuid(expire, 1, reqId, c);
                expire.executeUpdate();
            }

            sharedData.clear();
            int count = 0;
            try (PreparedStatement getRes = c.prepareStatement(
                    "SELECT s.subject_code, r.percentage, r.grade, r.created_at " +
                    "FROM results r " +
                    "JOIN exams e ON e.id = r.exam_id " +
                    "JOIN subjects s ON s.id = e.subject_id " +
                    "WHERE r.student_id = ? ORDER BY r.created_at DESC LIMIT 10")) {
                AuthService.setUuid(getRes, 1, senderId, c);
                ResultSet rs = getRes.executeQuery();
                while (rs.next()) {
                    count++;
                    sharedData.add(new SharedResultRow(
                        senderName, rs.getString(1),
                        String.format("%.1f%%", rs.getDouble(2)),
                        rs.getString(3) == null ? "-" : rs.getString(3),
                        rs.getTimestamp(4).toLocalDateTime().toLocalDate().toString()));
                }
            }
            setNotifStatus("Accepted! " + count +
                " results received - see the table below.", false);
            loadShareRequests();
        } catch (Exception e) {
            setNotifStatus("Error: " + e.getMessage(), true);
        }
    }

    /** FIX: declines ONLY the selected request. */
    @FXML
    private void declineShareRequest() {
        String selected = shareRequestsList.getSelectionModel().getSelectedItem();
        if (selected == null || selected.startsWith("No pending")) {
            setNotifStatus("Select a request to decline.", true); return;
        }
        try (Connection c = DatabaseManager.getConnection()) {
            String[] req = findSelectedRequest(c, selected);
            if (req == null) {
                setNotifStatus("Could not find that request.", true); return;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE announcements SET expires_at = CURRENT_TIMESTAMP WHERE id = ?")) {
                AuthService.setUuid(ps, 1, req[0], c);
                ps.executeUpdate();
            }
            setNotifStatus("Request declined.", false);
            loadShareRequests();
        } catch (Exception e) {
            setNotifStatus("Error: " + e.getMessage(), true);
        }
    }

    private void loadSharedResults() {
        if (receivedResultsTable != null)
            receivedResultsTable.setItems(sharedData);
    }

    @FXML
    private void loadNotifications() { loadShareRequests(); loadSharedResults(); }

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
                 "INSERT INTO result_appeals(id, result_id, student_id, " +
                 "subject_code, reason) VALUES(?,?,?,?,?)")) {
            AuthService.setUuid(ps, 1, UUID.randomUUID().toString(), c);
            AuthService.setUuid(ps, 2, sel.resultId, c);
            AuthService.setUuid(ps, 3, AuthService.Session.userId, c);
            ps.setString(4, sel.subject);
            ps.setString(5, appealText.getText().trim());
            ps.executeUpdate();
            appealText.clear();
            AuthService.logAudit("RESULT_APPEAL", "results", sel.resultId);
            new Alert(Alert.AlertType.INFORMATION,
                "Appeal submitted. Admin will review and respond.").showAndWait();
        } catch (Exception e) {
            new Alert(Alert.AlertType.ERROR, "Error: " + e.getMessage()).showAndWait();
        }
    }

    @FXML
    private void downloadReport() {
        try {
            String out = ReportCardPdf.generateForStudent(
                AuthService.Session.userId, null, "1st", "2024/2025");
            new Alert(Alert.AlertType.INFORMATION,
                "Report Card saved:\n" + out).showAndWait();
        } catch (Exception e) {
            new Alert(Alert.AlertType.ERROR, "Error: " + e.getMessage()).showAndWait();
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
            new Alert(Alert.AlertType.ERROR, "Error: " + e.getMessage()).showAndWait();
        }
    }

    @FXML
    private void startExam() {
        String selectedSubject = subjectBox == null ? null : subjectBox.getValue();
        if (selectedSubject == null || selectedSubject.isBlank()) {
            new Alert(Alert.AlertType.WARNING, "Please select a subject.").showAndWait();
            return;
        }
        String classLevel = classField == null ? "" : classField.getText();
        if (classLevel.isBlank()) {
            new Alert(Alert.AlertType.WARNING,
                "Your class is not set. Contact admin.").showAndWait();
            return;
        }
        try (Connection c = DatabaseManager.getConnection()) {
            String examId = null, examTitle = null;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT e.id, e.title FROM exams e " +
                    "JOIN subjects s ON s.id = e.subject_id " +
                    "WHERE (s.subject_name = ? OR s.subject_code = ?) " +
                    "  AND e.class_level = ? AND e.is_active = TRUE " +
                    "  AND e.is_practice = FALSE " +
                    "  AND (e.start_at IS NULL OR e.start_at <= CURRENT_TIMESTAMP) " +
                    "  AND (e.end_at IS NULL OR e.end_at >= CURRENT_TIMESTAMP) " +
                    "ORDER BY e.created_at DESC LIMIT 1")) {
                ps.setString(1, selectedSubject);
                ps.setString(2, selectedSubject);
                ps.setString(3, classLevel);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) { examId = rs.getString(1); examTitle = rs.getString(2); }
            }
            if (examId == null) {
                new Alert(Alert.AlertType.INFORMATION,
                    "No active exam found for " + selectedSubject +
                    " - " + classLevel).showAndWait();
                return;
            }
            try (PreparedStatement chk = c.prepareStatement(
                    "SELECT status FROM exam_attempts " +
                    "WHERE exam_id = ? AND student_id = ?")) {
                AuthService.setUuid(chk, 1, examId, c);
                AuthService.setUuid(chk, 2, AuthService.Session.userId, c);
                ResultSet chkRs = chk.executeQuery();
                if (chkRs.next()) {
                    String st = chkRs.getString(1);
                    if ("SUBMITTED".equals(st) || "MALPRACTICE".equals(st)) {
                        new Alert(Alert.AlertType.WARNING,
                            "You already completed: " + examTitle +
                            "\nStatus: " + st).showAndWait();
                        return;
                    }
                }
            }
            FXMLLoader loader = new FXMLLoader(
                getClass().getResource("/fxml/exam_instructions.fxml"));
            double w = welcomeLabel.getScene().getWindow().getWidth();
            double h = welcomeLabel.getScene().getWindow().getHeight();
            Scene scene = new Scene(loader.load(), w, h);
            scene.getStylesheets().add(
                getClass().getResource("/css/klc-premium.css").toExternalForm());
            ExamInstructionsController ctrl = loader.getController();
            ctrl.init(examId, "A");
            Stage st = (Stage) welcomeLabel.getScene().getWindow();
            st.setScene(scene);
        } catch (Exception e) {
            e.printStackTrace();
            new Alert(Alert.AlertType.ERROR, "Error: " + e.getMessage()).showAndWait();
        }
    }

    @FXML
    private void startPractice() {
        if (practiceSubjectBox == null || practiceClassBox == null) return;
        String subj = practiceSubjectBox.getValue();
        String cls  = practiceClassBox.getValue();
        if (subj == null || cls == null) {
            setPracticeStatus("Select both subject and class.", true); return;
        }
        try {
            FXMLLoader loader = new FXMLLoader(
                getClass().getResource("/fxml/practice_exam.fxml"));
            Scene scene = new Scene(loader.load(), 1100, 700);
            scene.getStylesheets().add(
                getClass().getResource("/css/klc-premium.css").toExternalForm());
            Stage st = new Stage();
            st.setTitle("KLC v1.0 - Practice Mode - " + subj);
            st.setScene(scene);
            st.show();
        } catch (Exception e) {
            setPracticeStatus("Error: " + e.getMessage(), true);
        }
    }

    @FXML
    private void logout() throws Exception {
        stopAutoRefresh();
        SessionIdleWatcher.stop();
        SyncService.stop();
        AuthService.Session.clear();
        MainApp.setRoot("login.fxml", null);
    }

    public void cleanup() { stopAutoRefresh(); }

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

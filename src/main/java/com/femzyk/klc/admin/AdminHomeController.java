package com.femzyk.klc.admin;

import com.femzyk.klc.db.DatabaseManager;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AdminHomeController - KLC CBT Suite v1.0 HOTFIX (lag fix)
 *
 * ROOT CAUSE OF "NOT RESPONDING": the 20s auto-refresh ran ALL its
 * database queries ON THE JAVAFX UI THREAD - 8+ network round-trips to
 * Supabase froze the window on every tick.
 *
 * FIX: all DB work now runs on a single DAEMON background thread; the
 * UI thread only receives ready-made results via Platform.runLater.
 * Overlapping refreshes are skipped (AtomicBoolean guard). The timer
 * and executor self-stop when the page is left. UI never blocks.
 * All features preserved: stat cards, approved/pending, per-subject
 * table, recent registrations, live dot, manual Refresh.
 */
public class AdminHomeController {

    private static final int REFRESH_SECONDS = 20;

    @FXML private Label lblStudents, lblExams, lblTeachers, lblQuestions;
    @FXML private Label lblLiveExams, lblNewRegistrations, lblMalpractice, lblHealth;
    @FXML private Label lastUpdatedLabel;
    @FXML private Circle liveDot;
    @FXML private Label liveStatusLabel;

    @FXML private TableView<RegistrationRow> recentRegistrationsTable;
    @FXML private TableColumn<RegistrationRow, String> colRegName, colRegRole, colRegTime;

    @FXML private TableView<SubjectQRow> subjectQuestionsTable;
    @FXML private TableColumn<SubjectQRow, String> colSqSubject, colSqClass,
                                                   colSqApproved, colSqPending, colSqTotal;

    private final ObservableList<RegistrationRow> registrationData =
            FXCollections.observableArrayList();
    private final ObservableList<SubjectQRow> subjectQData =
            FXCollections.observableArrayList();

    private Timeline autoRefresh;
    private ExecutorService bg;
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    public static class RegistrationRow {
        String name, role, time;
        RegistrationRow(String n, String r, String t) { name = n; role = r; time = t; }
        public String getName() { return name; }
        public String getRole() { return role; }
        public String getTime() { return time; }
    }

    public static class SubjectQRow {
        String subject, cls, approved, pending, total;
        SubjectQRow(String s, String c, String a, String p, String t) {
            subject = s; cls = c; approved = a; pending = p; total = t; }
        public String getSubject()  { return subject; }
        public String getCls()      { return cls; }
        public String getApproved() { return approved; }
        public String getPending()  { return pending; }
        public String getTotal()    { return total; }
    }

    /** Snapshot of everything, built off-thread. */
    private static class Snapshot {
        String students="--", exams="--", teachers="--", questions="--",
               live="--", newToday="--", malpractice="--", health="-";
        boolean cloud;
        List<RegistrationRow> regs = new ArrayList<>();
        List<SubjectQRow> subjects = new ArrayList<>();
    }

    @FXML
    public void initialize() {
        colRegName.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(c.getValue().getName()));
        colRegRole.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(c.getValue().getRole()));
        colRegTime.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(c.getValue().getTime()));
        recentRegistrationsTable.setItems(registrationData);

        if (subjectQuestionsTable != null) {
            colSqSubject.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().getSubject()));
            colSqClass.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().getCls()));
            colSqApproved.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().getApproved()));
            colSqPending.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().getPending()));
            colSqTotal.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().getTotal()));
            subjectQuestionsTable.setItems(subjectQData);
        }

        bg = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "klc-home-refresh");
            t.setDaemon(true);
            return t;
        });

        refreshAll();
        startAutoRefresh();
    }

    private void startAutoRefresh() {
        stopAutoRefresh(false);
        autoRefresh = new Timeline(new KeyFrame(
            Duration.seconds(REFRESH_SECONDS), e -> {
                if (lblStudents == null || lblStudents.getScene() == null) {
                    stopAutoRefresh(true);
                    return;
                }
                refreshAll();
            }));
        autoRefresh.setCycleCount(Timeline.INDEFINITE);
        autoRefresh.play();
    }

    private void stopAutoRefresh(boolean shutdownExecutor) {
        if (autoRefresh != null) { autoRefresh.stop(); autoRefresh = null; }
        if (shutdownExecutor && bg != null) { bg.shutdown(); }
    }

    /** UI-thread entry point: schedules the work OFF the UI thread. */
    @FXML
    public void refreshAll() {
        if (bg == null || bg.isShutdown()) return;
        if (!refreshing.compareAndSet(false, true)) return; // skip overlap
        bg.submit(() -> {
            Snapshot s = buildSnapshot();      // background thread - may be slow, UI unaffected
            Platform.runLater(() -> {
                applySnapshot(s);              // UI thread - instant
                refreshing.set(false);
            });
        });
    }

    /** ALL database work happens here, off the UI thread. */
    private Snapshot buildSnapshot() {
        Snapshot s = new Snapshot();
        s.cloud = DatabaseManager.isCloudAvailable();
        try (java.sql.Connection c = DatabaseManager.getConnection()) {
            s.students    = one(c, "SELECT COUNT(*) FROM student_profiles");
            s.exams       = one(c, "SELECT COUNT(*) FROM exams WHERE is_active = true");
            s.teachers    = one(c, "SELECT COUNT(*) FROM users WHERE role = 'TEACHER'");
            s.live        = one(c, "SELECT COUNT(*) FROM exam_attempts WHERE status = 'IN_PROGRESS'");
            s.newToday    = one(c, "SELECT COUNT(*) FROM student_profiles WHERE created_at >= CURRENT_DATE");
            s.malpractice = one(c, "SELECT COUNT(*) FROM exam_attempts WHERE status = 'MALPRACTICE'");

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT SUM(CASE WHEN is_approved = true THEN 1 ELSE 0 END), " +
                    "       SUM(CASE WHEN is_approved = true THEN 0 ELSE 1 END) " +
                    "FROM questions");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) s.questions = rs.getInt(1) + " / " + rs.getInt(2);
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT full_name, role, created_at FROM users " +
                    "ORDER BY created_at DESC LIMIT 10");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp(3);
                    s.regs.add(new RegistrationRow(
                        rs.getString(1), rs.getString(2),
                        ts == null ? "-" : ts.toLocalDateTime()
                            .format(DateTimeFormatter.ofPattern("dd MMM HH:mm"))));
                }
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT s.subject_name, q.class_level, " +
                    "  SUM(CASE WHEN q.is_approved = true THEN 1 ELSE 0 END), " +
                    "  SUM(CASE WHEN q.is_approved = true THEN 0 ELSE 1 END), " +
                    "  COUNT(*) " +
                    "FROM questions q JOIN subjects s ON s.id = q.subject_id " +
                    "GROUP BY s.subject_name, q.class_level " +
                    "ORDER BY s.subject_name, q.class_level");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    s.subjects.add(new SubjectQRow(
                        rs.getString(1),
                        rs.getString(2) == null ? "-" : rs.getString(2),
                        String.valueOf(rs.getInt(3)),
                        String.valueOf(rs.getInt(4)),
                        String.valueOf(rs.getInt(5))));
                }
            }
            s.health = s.cloud ? "Good" : "Offline";
        } catch (Exception e) {
            s.health = "Error";
            s.cloud = false;
        }
        return s;
    }

    private String one(java.sql.Connection c, String sql) {
        try (PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return String.valueOf(rs.getInt(1));
        } catch (Exception ignored) {}
        return "--";
    }

    /** UI thread only - pure setText/list swaps, zero blocking. */
    private void applySnapshot(Snapshot s) {
        lblStudents.setText(s.students);
        lblExams.setText(s.exams);
        lblTeachers.setText(s.teachers);
        lblQuestions.setText(s.questions);
        lblLiveExams.setText(s.live);
        lblNewRegistrations.setText(s.newToday);
        lblMalpractice.setText(s.malpractice);
        lblHealth.setText(s.health);
        registrationData.setAll(s.regs);
        subjectQData.setAll(s.subjects);
        if (lastUpdatedLabel != null)
            lastUpdatedLabel.setText("Auto-refresh " + REFRESH_SECONDS +
                "s (background) | Last Updated: " +
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        if (liveDot != null && liveStatusLabel != null) {
            liveDot.setFill(Color.web(s.cloud ? "#10b981" : "#f59e0b"));
            liveStatusLabel.setText(s.cloud ? "Live" : "Offline");
            liveStatusLabel.setStyle("-fx-text-fill: " +
                (s.cloud ? "#10b981" : "#f59e0b") + "; -fx-font-weight: bold;");
        }
    }
}

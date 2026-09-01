package com.femzyk.klc.admin;

import com.femzyk.klc.MainApp;
import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.auth.SessionIdleWatcher;
import com.femzyk.klc.util.SyncService;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * AdminDashboardController v1.0
 *
 * FIXES:
 * 1. RULE 3 ENFORCED: this is now the ONLY class in the application that
 *    owns an OkHttpClient/WebSocket. AdminHome and QuestionBank no longer
 *    open their own sockets (that was the source of the OkHttp
 *    "connection leaked" warnings - new sockets every page visit, never
 *    closed on navigation).
 * 2. SECRET REMOVED: the Supabase URL + anon key are no longer hardcoded.
 *    They are read from config.properties (supabase.url / supabase.key).
 *    If missing, realtime is skipped gracefully - the app works fine.
 * 3. LEAK FIXED: onFailure now closes the Response body.
 * 4. ROLE-BASED SIDEBAR (Priority 1 #2): every sidebar section/button is
 *    shown or hidden per the handover permission matrix for ALL roles:
 *      SUPER_ADMIN     -> everything
 *      PRINCIPAL_ADMIN -> everything except Create User
 *      EXAM_OFFICER    -> Live Monitor, Analytics, Question Bank (view),
 *                         Exam Manager, CA Scores, Results, Broadsheet,
 *                         Notifications, About
 *      TEACHER         -> Question Bank/Editor/Import (own), Study
 *                         Materials, Exam Manager (own), CA Scores (own),
 *                         Results (own), Students (view), Notifications,
 *                         About
 *    Each role also gets an appropriate landing page.
 * 5. Every navigation target is ALSO guarded in code (allowed() check) so
 *    hiding a button is never the only line of defence.
 */
public class AdminDashboardController {

    @FXML private StackPane contentPane;
    @FXML private Label     sessionStatusLabel;
    @FXML private Label     portalSubtitle;

    // ── Sidebar sections ──
    @FXML private Label secOverview, secAcademic, secQuestions,
                        secExams, secResults, secPeople, secSystem;

    // ── Sidebar buttons ──
    @FXML private Button btnDashboard, btnLiveMonitor, btnAnalytics,
                         btnSubjects, btnClassMgr, btnGrading,
                         btnQBank, btnQEditor, btnImporter, btnStudyMat,
                         btnExamMgr, btnCaScores,
                         btnResults, btnBroadsheet, btnAppeals,
                         btnStudents, btnTeachers, btnIdCards, btnCreateUser,
                         btnNotifications, btnSettings, btnAuditLogs,
                         btnBackup, btnHealth, btnAbout;

    // RULE 3: the single application-wide OkHttp client.
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build();

    /** Exposed so no other controller ever builds its own client. */
    public static OkHttpClient httpClient() { return HTTP_CLIENT; }

    private WebSocket webSocket;

    @FXML
    public void initialize() {
        applyRoleVisibility();
        landForRole();

        if (MainApp.primaryStage != null &&
                MainApp.primaryStage.getScene() != null) {
            SessionIdleWatcher.start(
                MainApp.primaryStage.getScene(), () -> {});
        }

        SyncService.startAutoSync(sessionStatusLabel);
        connectRealtime();
    }

    // =========================================================================
    //  ROLE-BASED SIDEBAR (handover permission matrix)
    // =========================================================================
    private void applyRoleVisibility() {
        String role = AuthService.Session.role == null
                    ? "" : AuthService.Session.role;

        if (portalSubtitle != null) {
            String label = switch (role) {
                case "SUPER_ADMIN"     -> "CBT SUITE v1.0 - Super Admin Portal";
                case "PRINCIPAL_ADMIN" -> "CBT SUITE v1.0 - Principal Portal";
                case "EXAM_OFFICER"    -> "CBT SUITE v1.0 - Exam Officer Portal";
                case "TEACHER"         -> "CBT SUITE v1.0 - Teacher Portal";
                default                -> "CBT SUITE v1.0 - Admin Portal";
            };
            portalSubtitle.setText(label);
        }

        if ("SUPER_ADMIN".equals(role)) return; // sees everything

        if ("PRINCIPAL_ADMIN".equals(role)) {
            hide(btnCreateUser);
            return;
        }

        if ("EXAM_OFFICER".equals(role)) {
            hide(btnDashboard);
            hide(secAcademic); hide(btnSubjects); hide(btnClassMgr); hide(btnGrading);
            hide(btnQEditor); hide(btnImporter); hide(btnStudyMat);
            hide(btnAppeals);
            hide(secPeople); hide(btnStudents); hide(btnTeachers);
            hide(btnIdCards); hide(btnCreateUser);
            hide(btnSettings); hide(btnAuditLogs); hide(btnBackup); hide(btnHealth);
            return;
        }

        if ("TEACHER".equals(role)) {
            hide(btnDashboard); hide(btnLiveMonitor); hide(btnAnalytics);
            hide(secOverview);
            hide(secAcademic); hide(btnSubjects); hide(btnClassMgr); hide(btnGrading);
            hide(btnBroadsheet); hide(btnAppeals);
            hide(btnTeachers); hide(btnIdCards); hide(btnCreateUser);
            hide(btnSettings); hide(btnAuditLogs); hide(btnBackup); hide(btnHealth);
            return;
        }
    }

    private void hide(Node n) {
        if (n != null) { n.setVisible(false); n.setManaged(false); }
    }

    /** Landing page per role. */
    private void landForRole() {
        String role = AuthService.Session.role == null
                    ? "" : AuthService.Session.role;
        switch (role) {
            case "EXAM_OFFICER" -> load("live_monitor.fxml");
            case "TEACHER"      -> load("question_bank.fxml");
            default             -> load("admin_home.fxml");
        }
    }

    /** Code-level guard - hiding a button is never the only defence. */
    private boolean allowed(String feature) {
        String r = AuthService.Session.role == null
                 ? "" : AuthService.Session.role;
        if ("SUPER_ADMIN".equals(r)) return true;
        return switch (feature) {
            case "dashboard", "subjects", "classes", "grading",
                 "broadsheet", "appeals", "teachers", "idcards",
                 "settings", "audit", "backup", "health"
                -> "PRINCIPAL_ADMIN".equals(r);
            case "livemonitor", "analytics"
                -> "PRINCIPAL_ADMIN".equals(r) || "EXAM_OFFICER".equals(r);
            case "qbank", "exams", "cascores", "results", "notifications", "about"
                -> true; // all staff roles reach here
            case "qeditor", "importer", "studymat", "students"
                -> "PRINCIPAL_ADMIN".equals(r) || "TEACHER".equals(r);
            case "createuser" -> false; // super admin only
            default -> false;
        };
    }

    private void denied() {
        Label d = new Label(
            "Access Denied\nYour role does not have permission for this page.");
        d.setStyle("-fx-text-fill:#c0392b; -fx-font-size:16px;" +
                   "-fx-font-weight:bold; -fx-padding:40;");
        contentPane.getChildren().setAll(d);
    }

    // =========================================================================
    //  REALTIME - single socket, credentials from config.properties only
    // =========================================================================
    private void connectRealtime() {
        closeWebSocket();
        try {
            Properties p = new Properties();
            try (InputStream in = getClass()
                    .getResourceAsStream("/config.properties")) {
                if (in != null) p.load(in);
            }
            String base = p.getProperty("supabase.url", "").trim();
            String key  = p.getProperty("supabase.key", "").trim();
            if (base.isBlank() || key.isBlank()) {
                System.out.println("[WS] Realtime skipped - no config");
                return;
            }
            String url = base.replaceFirst("^https", "wss")
                       + "/realtime/v1/websocket?apikey=" + key + "&vsn=1.0.0";

            webSocket = HTTP_CLIENT.newWebSocket(
                new Request.Builder().url(url).build(),
                new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket ws, Response r) {
                        r.close(); // FIX: never leak the handshake response
                        subscribeToTable(ws, "exam_attempts");
                        subscribeToTable(ws, "results");
                        subscribeToTable(ws, "users");
                        subscribeToTable(ws, "student_profiles");
                        subscribeToTable(ws, "questions");
                        subscribeToTable(ws, "exams");
                    }
                    @Override
                    public void onMessage(WebSocket ws, String text) {
                        if (text.contains("exam_attempts") ||
                            text.contains("results")       ||
                            text.contains("users")         ||
                            text.contains("student_profiles")) {
                            Platform.runLater(() -> {
                                if (contentPane != null &&
                                        !contentPane.getChildren().isEmpty()) {
                                    Node n = contentPane.getChildren().get(0);
                                    if ("admin_home".equals(n.getUserData()))
                                        load("admin_home.fxml");
                                }
                            });
                        }
                    }
                    @Override
                    public void onFailure(WebSocket ws, Throwable t,
                                          Response r) {
                        if (r != null) r.close(); // FIX: the leaked response
                        System.out.println("[WS] Disconnected: " +
                            (t == null ? "?" : t.getMessage()));
                    }
                });
        } catch (Exception ignored) {}
    }

    private void subscribeToTable(WebSocket ws, String table) {
        ws.send("{\"topic\":\"realtime:public:" + table + "\"," +
                "\"event\":\"phx_join\",\"payload\":{}," +
                "\"ref\":\"" + System.currentTimeMillis() + "\"}");
    }

    private void closeWebSocket() {
        if (webSocket != null) {
            try { webSocket.close(1000, "Closing"); }
            catch (Exception ignored) {}
            webSocket = null;
        }
    }

    private void load(String fxml) {
        try {
            FXMLLoader loader = new FXMLLoader(
                getClass().getResource("/fxml/admin/" + fxml));
            Node n = loader.load();
            if ("admin_home.fxml".equals(fxml)) n.setUserData("admin_home");
            contentPane.getChildren().setAll(n);
            AuthService.Session.touch();
        } catch (Exception e) {
            e.printStackTrace();
            Label err = new Label(
                "Error loading " + fxml + "\n" + e.getMessage());
            err.setStyle("-fx-text-fill:#c0392b; -fx-font-size:13px;" +
                         "-fx-padding:20; -fx-wrap-text:true;");
            contentPane.getChildren().setAll(err);
        }
    }

    // ── Navigation (each guarded) ─────────────────────────────────────────
    @FXML private void showDashboard()      { if (allowed("dashboard"))    load("admin_home.fxml");        else denied(); }
    @FXML private void showSubjects()       { if (allowed("subjects"))     load("subject_manager.fxml");   else denied(); }
    @FXML private void showClassManager()   { if (allowed("classes"))      load("class_manager.fxml");     else denied(); }
    @FXML private void showGradingScale()   { if (allowed("grading"))      load("grading_scale.fxml");     else denied(); }
    @FXML private void showQuestions()      { if (allowed("qbank"))        load("question_bank.fxml");     else denied(); }
    @FXML private void showQuestionEditor() { if (allowed("qeditor"))      load("question_editor.fxml");   else denied(); }
    @FXML private void showImporter()       { if (allowed("importer"))     load("question_importer.fxml"); else denied(); }
    @FXML private void showStudyMaterials() { if (allowed("studymat"))     load("study_materials.fxml");   else denied(); }
    @FXML private void showExams()          { if (allowed("exams"))        load("exam_manager.fxml");      else denied(); }
    @FXML private void showLiveMonitor()    { if (allowed("livemonitor"))  load("live_monitor.fxml");      else denied(); }
    @FXML private void showCaScores()       { if (allowed("cascores"))     load("ca_scores.fxml");         else denied(); }
    @FXML private void showBroadsheet()     { if (allowed("broadsheet"))   load("broadsheet.fxml");        else denied(); }
    @FXML private void showResults()        { if (allowed("results"))      load("results_view.fxml");      else denied(); }
    @FXML private void showAppeals()        { if (allowed("appeals"))      load("appeals.fxml");           else denied(); }
    @FXML private void showStudents()       { if (allowed("students"))     load("student_manager.fxml");   else denied(); }
    @FXML private void showTeachers()       { if (allowed("teachers"))     load("teacher_manager.fxml");   else denied(); }
    @FXML private void showTeacherImport()  { if (allowed("teachers"))     load("teacher_import.fxml");    else denied(); }
    @FXML private void showIdCards()        { if (allowed("idcards"))      load("id_cards.fxml");          else denied(); }
    @FXML private void showAnalytics()      { if (allowed("analytics"))    load("analytics.fxml");         else denied(); }
    @FXML private void showNotifications()  { if (allowed("notifications")) load("notifications.fxml");    else denied(); }
    @FXML private void showAuditLogs()      { if (allowed("audit"))        load("audit_logs.fxml");        else denied(); }
    @FXML private void showBackup()         { if (allowed("backup"))       load("backup.fxml");            else denied(); }
    @FXML private void showHealthMonitor()  { if (allowed("health"))       load("health_monitor.fxml");    else denied(); }
    @FXML private void showSettings()       { if (allowed("settings"))     load("school_settings.fxml");   else denied(); }
    @FXML private void showAbout()          { load("about.fxml"); }
    @FXML private void showProfile()  { loadSocial("profile.fxml"); }
    @FXML private void showFriends()  { loadSocial("friends.fxml"); }
    @FXML private void showMessages() { loadSocial("messages.fxml"); }

    private void loadSocial(String fxml) {
        try {
            javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(
                getClass().getResource("/fxml/social/" + fxml));
            javafx.scene.Node n = loader.load();
            contentPane.getChildren().setAll(n);
            AuthService.Session.touch();
        } catch (Exception e) {
            e.printStackTrace();
            Label err = new Label("Error loading " + fxml + "\n" + e.getMessage());
            err.setStyle("-fx-text-fill:#c0392b; -fx-font-size:13px;" +
                         "-fx-padding:20; -fx-wrap-text:true;");
            contentPane.getChildren().setAll(err);
        }
    }

    @FXML private void showManualUserCreation() {
        if (!AuthService.isSuperAdmin()) { denied(); return; }
        load("manual_user_creation.fxml");
    }

    @FXML
    private void logout() throws Exception {
        closeWebSocket();
        SessionIdleWatcher.stop();
        SyncService.stop();
        AuthService.Session.clear();
        MainApp.setRoot("login.fxml", null);
    }
}

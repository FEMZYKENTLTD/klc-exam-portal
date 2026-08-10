package com.femzyk.klc.admin;

import com.femzyk.klc.MainApp;
import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.auth.SessionIdleWatcher;
import com.femzyk.klc.util.SyncService;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import java.util.concurrent.TimeUnit;

public class AdminDashboardController {

    @FXML private StackPane contentPane;
    @FXML private Label     sessionStatusLabel;

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build();

    private WebSocket webSocket;

    @FXML
    public void initialize() {
        showDashboard();

        if (MainApp.primaryStage != null &&
                MainApp.primaryStage.getScene() != null) {
            SessionIdleWatcher.start(
                MainApp.primaryStage.getScene(), () -> {});
        }

        SyncService.startAutoSync(sessionStatusLabel);
        connectRealtime();
    }

    private void connectRealtime() {
        closeWebSocket();
        String url =
            "wss://aqircycpctadgvbqsadf.supabase.co/realtime/v1/websocket" +
            "?apikey=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9" +
            ".eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImFxaXJjeWNwY3RhZGd2YnFzYWRmIiwi" +
            "cm9sZSI6ImFub24iLCJpYXQiOjE3ODIxNDM0OTMsImV4cCI6MjA5NzcxOTQ5M30" +
            ".mn9pn4bmx8R860K2KZx-MEe-G0U7o4ZYZxwwO6p7sjg&vsn=1.0.0";

        try {
            webSocket = HTTP_CLIENT.newWebSocket(
                new Request.Builder().url(url).build(),
                new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket ws, Response r) {
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
                        System.out.println("[WS] Disconnected: " +
                            t.getMessage());
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

    // ── Navigation ────────────────────────────────────────────────────────────
    @FXML private void showDashboard()          { load("admin_home.fxml"); }
    @FXML private void showSubjects()           { load("subject_manager.fxml"); }
    @FXML private void showClassManager()       { load("class_manager.fxml"); }
    @FXML private void showGradingScale()       { load("grading_scale.fxml"); }
    @FXML private void showQuestions()          { load("question_bank.fxml"); }
    @FXML private void showQuestionEditor()     { load("question_editor.fxml"); }
    @FXML private void showImporter()           { load("question_importer.fxml"); }
    @FXML private void showStudyMaterials()     { load("study_materials.fxml"); }
    @FXML private void showExams()              { load("exam_manager.fxml"); }
    @FXML private void showLiveMonitor()        { load("live_monitor.fxml"); }
    @FXML private void showCaScores()           { load("ca_scores.fxml"); }
    @FXML private void showBroadsheet()         { load("broadsheet.fxml"); }
    @FXML private void showResults()            { load("results_view.fxml"); }
    @FXML private void showAppeals()            { load("appeals.fxml"); }
    @FXML private void showStudents()           { load("student_manager.fxml"); }
    @FXML private void showTeachers()           { load("teacher_manager.fxml"); }
    @FXML private void showTeacherImport()      { load("teacher_import.fxml"); }
    @FXML private void showIdCards()            { load("id_cards.fxml"); }
    @FXML private void showAnalytics()          { load("analytics.fxml"); }
    @FXML private void showNotifications()      { load("notifications.fxml"); }
    @FXML private void showAuditLogs()          { load("audit_logs.fxml"); }
    @FXML private void showBackup()             { load("backup.fxml"); }
    @FXML private void showHealthMonitor()      { load("health_monitor.fxml"); }
    @FXML private void showSettings()           { load("school_settings.fxml"); }
    @FXML private void showAbout()              { load("about.fxml"); }

    // NEW: Manual User Creation (Super Admin only)
    @FXML private void showManualUserCreation() {
        if (!AuthService.isSuperAdmin()) {
            Label denied = new Label(
                "Access Denied\nOnly Super Admin can create users manually.");
            denied.setStyle(
                "-fx-text-fill:#c0392b; -fx-font-size:16px;" +
                "-fx-font-weight:bold; -fx-padding:40;");
            contentPane.getChildren().setAll(denied);
            return;
        }
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
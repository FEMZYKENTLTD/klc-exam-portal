package com.femzyk.klc.admin;

import com.femzyk.klc.db.DatabaseManager;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import okhttp3.*;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

public class AdminHomeController {

    // ─── Live Counter Labels ──────────────────────────────────────────────────
    @FXML private Label lblStudents, lblExams, lblTeachers, lblQuestions;
    @FXML private Label lblLiveExams, lblNewRegistrations, lblMalpractice, lblHealth;
    @FXML private Label lastUpdatedLabel;

    // ─── Live Connection Indicator ────────────────────────────────────────────
    @FXML private Circle liveDot;
    @FXML private Label liveStatusLabel;

    // ─── Recent Registrations Table ───────────────────────────────────────────
    @FXML private TableView<RegistrationRow> recentRegistrationsTable;
    @FXML private TableColumn<RegistrationRow, String> colRegName, colRegRole, colRegTime;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    private WebSocket webSocket;

    private final ObservableList<RegistrationRow> registrationData =
            FXCollections.observableArrayList();

    public static class RegistrationRow {
        String name, role, time;
        RegistrationRow(String n, String r, String t) { name = n; role = r; time = t; }
        public String getName() { return name; }
        public String getRole() { return role; }
        public String getTime() { return time; }
    }

    @FXML
    public void initialize() {
        setupRegistrationTable();
        loadAllStats();
        loadRecentRegistrations();
        startRealtimeUpdates();
    }

    private void setupRegistrationTable() {
        colRegName.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(c.getValue().getName()));
        colRegRole.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(c.getValue().getRole()));
        colRegTime.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(c.getValue().getTime()));
        recentRegistrationsTable.setItems(registrationData);
    }

    private void loadAllStats() {
        try (java.sql.Connection c = DatabaseManager.getConnection()) {

            // Total Students
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM student_profiles")) {
                ResultSet rs = ps.executeQuery();
                if (rs.next()) lblStudents.setText(String.valueOf(rs.getInt(1)));
            }

            // Active Exams
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM exams WHERE is_active = true")) {
                ResultSet rs = ps.executeQuery();
                if (rs.next()) lblExams.setText(String.valueOf(rs.getInt(1)));
            }

            // Teachers
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM users WHERE role = 'TEACHER'")) {
                ResultSet rs = ps.executeQuery();
                if (rs.next()) lblTeachers.setText(String.valueOf(rs.getInt(1)));
            }

            // Questions in Bank
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM questions WHERE is_approved = true")) {
                ResultSet rs = ps.executeQuery();
                if (rs.next()) lblQuestions.setText(String.valueOf(rs.getInt(1)));
            }

            // Live Exams Now
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM exam_attempts WHERE status = 'IN_PROGRESS'")) {
                ResultSet rs = ps.executeQuery();
                if (rs.next()) lblLiveExams.setText(String.valueOf(rs.getInt(1)));
            }

            // New Registrations Today
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM student_profiles WHERE created_at >= CURRENT_DATE")) {
                ResultSet rs = ps.executeQuery();
                if (rs.next()) lblNewRegistrations.setText(String.valueOf(rs.getInt(1)));
            }

            // Malpractice Cases
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM exam_attempts WHERE status = 'MALPRACTICE'")) {
                ResultSet rs = ps.executeQuery();
                if (rs.next()) lblMalpractice.setText(String.valueOf(rs.getInt(1)));
            }

            lblHealth.setText("Good");
            updateTimestamp();

        } catch (Exception e) {
            e.printStackTrace();
            setLiveStatus(false);
        }
    }

    private void loadRecentRegistrations() {
        registrationData.clear();
        try (java.sql.Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT full_name, role, created_at FROM users " +
                 "ORDER BY created_at DESC LIMIT 10")) {

            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String time = rs.getTimestamp("created_at").toLocalDateTime()
                        .format(DateTimeFormatter.ofPattern("dd MMM HH:mm"));
                registrationData.add(new RegistrationRow(
                    rs.getString("full_name"),
                    rs.getString("role"),
                    time
                ));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startRealtimeUpdates() {
        String url = "wss://aqircycpctadgvbqsadf.supabase.co/realtime/v1/websocket" +
                "?apikey=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImFxaXJjeWNwY3RhZGd2YnFzYWRmIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODIxNDM0OTMsImV4cCI6MjA5NzcxOTQ5M30.mn9pn4bmx8R860K2KZx-MEe-G0U7o4ZYZxwwO6p7sjg" +
                "&vsn=1.0.0";

        Request request = new Request.Builder().url(url).build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                Platform.runLater(() -> setLiveStatus(true));

                subscribeToTable("exam_attempts");
                subscribeToTable("results");
                subscribeToTable("student_profiles");
                subscribeToTable("users");
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                if (text.contains("student_profiles") || text.contains("users")) {
                    Platform.runLater(() -> {
                        loadAllStats();
                        loadRecentRegistrations();
                        updateTimestamp();
                    });
                } else if (text.contains("exam_attempts") || text.contains("results")) {
                    Platform.runLater(() -> {
                        loadAllStats();
                        updateTimestamp();
                    });
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                Platform.runLater(() -> setLiveStatus(false));
            }
        });
    }

    private void subscribeToTable(String table) {
        String msg = "{\"topic\":\"realtime:public:" + table + "\",\"event\":\"phx_join\",\"payload\":{},\"ref\":\"" + System.currentTimeMillis() + "\"}";
        webSocket.send(msg);
    }

    private void setLiveStatus(boolean isLive) {
        if (liveDot != null && liveStatusLabel != null) {
            if (isLive) {
                liveDot.setFill(Color.web("#10b981"));
                liveStatusLabel.setText("Live");
                liveStatusLabel.setStyle("-fx-text-fill: #10b981; -fx-font-weight: bold;");
            } else {
                liveDot.setFill(Color.web("#ef4444"));
                liveStatusLabel.setText("Offline");
                liveStatusLabel.setStyle("-fx-text-fill: #ef4444; -fx-font-weight: bold;");
            }
        }
    }

    private void updateTimestamp() {
        if (lastUpdatedLabel != null) {
            lastUpdatedLabel.setText("Last Updated: " + 
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        }
    }

    public void cleanup() {
        if (webSocket != null) {
            webSocket.close(1000, "Closing");
        }
    }
}
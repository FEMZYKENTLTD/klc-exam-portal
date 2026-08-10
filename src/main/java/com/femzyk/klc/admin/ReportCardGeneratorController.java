package com.femzyk.klc.admin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import com.femzyk.klc.db.DatabaseManager;
import com.femzyk.klc.util.ReportCardService;

import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

public class ReportCardGeneratorController {

    @FXML private TextField admissionField;
    @FXML private ComboBox<String> termBox;
    @FXML private ComboBox<String> sessionBox;
    @FXML private Label statusLabel;

    @FXML
    public void initialize() {
        termBox.getItems().addAll("1st", "2nd", "3rd");
        sessionBox.getItems().addAll("2024/2025", "2025/2026");
    }

    @FXML
    private void generateReportCard() {
        String admission = admissionField.getText().trim();
        String term = termBox.getValue();
        String session = sessionBox.getValue();

        if (admission.isEmpty() || term == null || session == null) {
            statusLabel.setText("Please fill all fields");
            statusLabel.setStyle("-fx-text-fill: #ef4444;");
            return;
        }

        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT user_id FROM student_profiles WHERE admission_no = ?")) {

            ps.setString(1, admission);
            ResultSet rs = ps.executeQuery();

            if (rs.next()) {
                String userId = rs.getString(1);
                String pdfPath = ReportCardService.generateReportCard(userId, term, session);

                if (pdfPath != null) {
                    statusLabel.setText("Report Card generated successfully!");
                    statusLabel.setStyle("-fx-text-fill: #10b981;");
                } else {
                    statusLabel.setText("Failed to generate Report Card");
                    statusLabel.setStyle("-fx-text-fill: #ef4444;");
                }
            } else {
                statusLabel.setText("Student not found");
                statusLabel.setStyle("-fx-text-fill: #ef4444;");
            }

        } catch (Exception e) {
            statusLabel.setText("Error: " + e.getMessage());
            statusLabel.setStyle("-fx-text-fill: #ef4444;");
        }
    }

    @FXML
    private void generateTranscript() {
        String admission = admissionField.getText().trim();

        if (admission.isEmpty()) {
            statusLabel.setText("Please enter Admission Number");
            statusLabel.setStyle("-fx-text-fill: #ef4444;");
            return;
        }

        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT user_id FROM student_profiles WHERE admission_no = ?")) {

            ps.setString(1, admission);
            ResultSet rs = ps.executeQuery();

            if (rs.next()) {
                String userId = rs.getString(1);
                String pdfPath = ReportCardService.generateTranscript(userId);

                if (pdfPath != null) {
                    statusLabel.setText("Transcript generated successfully!");
                    statusLabel.setStyle("-fx-text-fill: #10b981;");
                } else {
                    statusLabel.setText("Failed to generate Transcript");
                    statusLabel.setStyle("-fx-text-fill: #ef4444;");
                }
            } else {
                statusLabel.setText("Student not found");
                statusLabel.setStyle("-fx-text-fill: #ef4444;");
            }

        } catch (Exception e) {
            statusLabel.setText("Error: " + e.getMessage());
            statusLabel.setStyle("-fx-text-fill: #ef4444;");
        }
    }
}
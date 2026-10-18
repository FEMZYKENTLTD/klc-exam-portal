package com.femzyk.klc.admin;

import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.db.DatabaseManager;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import java.sql.*;

public class SuperAdminExamController {

    @FXML private TableView<ExamRow> examTable;
    @FXML private TableColumn<ExamRow, String> colTitle, colSubject, colClass, colStatus, colDuration;
    @FXML private TableColumn<ExamRow, String> colActions;
    @FXML private Label statusLabel;

    private ObservableList<ExamRow> data = FXCollections.observableArrayList();

    public static class ExamRow {
        String id, title, subject, classLevel, status, duration;

        ExamRow(String id, String t, String s, String c, String st, String d) {
            this.id = id; title = t; subject = s; classLevel = c; status = st; duration = d;
        }

        public String getTitle() { return title; }
        public String getSubject() { return subject; }
        public String getClassLevel() { return classLevel; }
        public String getStatus() { return status; }
        public String getDuration() { return duration; }
    }

    @FXML
    public void initialize() {
        colTitle.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getTitle()));
        colSubject.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getSubject()));
        colClass.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getClassLevel()));
        colStatus.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getStatus()));
        colDuration.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getDuration()));

        examTable.setItems(data);
        loadAllExams();
    }

    private void loadAllExams() {
        data.clear();
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT e.id, e.title, s.subject_name, e.class_level, e.is_active, e.duration_minutes " +
                     "FROM exams e JOIN subjects s ON s.id = e.subject_id " +
                     "ORDER BY e.created_at DESC")) {

            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String status = rs.getBoolean("is_active") ? "Active" : "Inactive";
                data.add(new ExamRow(
                    rs.getString("id"),
                    rs.getString("title"),
                    rs.getString("subject_name"),
                    rs.getString("class_level"),
                    status,
                    rs.getInt("duration_minutes") + " mins"
                ));
            }
        } catch (Exception e) {
            statusLabel.setText("Error loading exams: " + e.getMessage());
        }
    }

    @FXML
    private void cancelExam() {
        ExamRow selected = examTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            statusLabel.setText("Please select an exam to cancel");
            return;
        }

        if (!AuthService.isSuperAdmin()) {
            statusLabel.setText("Only Super Admin can cancel exams");
            return;
        }

        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE exams SET is_active = false WHERE id = ?")) {

            AuthService.setUuid(ps, 1, selected.id, c);
            ps.executeUpdate();

            statusLabel.setText("Exam cancelled successfully!");
            loadAllExams();

        } catch (Exception e) {
            statusLabel.setText("Error cancelling exam: " + e.getMessage());
        }
    }

    @FXML
    private void testExam() {
        ExamRow selected = examTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            statusLabel.setText("Please select an exam to test");
            return;
        }

        if (!AuthService.isSuperAdmin()) {
            statusLabel.setText("Only Super Admin can test exams");
            return;
        }

        // REAL exam dry-run validation (replaces the old simulated stub):
        // verifies the paper is student-ready before it is published.
        try (Connection c = DatabaseManager.getConnection()) {
            int questions = 0, broken = 0, noCorrect = 0;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT q.id FROM exam_questions eq JOIN questions q ON q.id = eq.question_id WHERE eq.exam_id = ?")) {
                AuthService.setUuid(ps, 1, selected.id, c);
                ResultSet rs = ps.executeQuery();
                java.util.List<String> qids = new java.util.ArrayList<>();
                while (rs.next()) qids.add(rs.getString(1));
                questions = qids.size();
                for (String qid : qids) {
                    try (PreparedStatement po = c.prepareStatement(
                            "SELECT COUNT(*), SUM(CASE WHEN is_correct = TRUE THEN 1 ELSE 0 END) FROM question_options WHERE question_id = ?")) {
                        AuthService.setUuid(po, 1, qid, c);
                        ResultSet ro = po.executeQuery();
                        if (ro.next()) {
                            if (ro.getInt(1) < 2) broken++;
                            if (ro.getInt(2) < 1) noCorrect++;
                        }
                    }
                }
            }
            if (questions == 0)      statusLabel.setText("DRY-RUN FAIL: '" + selected.title + "' has NO questions attached.");
            else if (broken > 0 || noCorrect > 0)
                statusLabel.setText("DRY-RUN FAIL: " + questions + " questions, " + broken + " with <2 options, " + noCorrect + " with no correct option - fix in Question Editor.");
            else statusLabel.setText("DRY-RUN PASS: '" + selected.title + "' is student-ready (" + questions + " questions, every question has options + a correct answer).");
            AuthService.logAudit("EXAM_DRY_RUN", "exams", selected.id);
        } catch (Exception e) {
            statusLabel.setText("Dry-run error: " + e.getMessage());
        }
    }

    @FXML
    private void refreshExams() {
        loadAllExams();
        statusLabel.setText("Exam list refreshed");
    }
}
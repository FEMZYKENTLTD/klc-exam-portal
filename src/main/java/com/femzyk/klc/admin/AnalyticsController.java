package com.femzyk.klc.admin;

import com.femzyk.klc.db.DatabaseManager;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;

import java.sql.*;

public class AnalyticsController {

    @FXML private Label statStudents, statExams, statQuestions,
                        statResults, statMalpractice;
    @FXML private TextArea reportArea;

    @FXML
    public void initialize() {
        refresh();
    }

    @FXML
    private void refresh() {
        try (Connection c = DatabaseManager.getConnection()) {

            if (statStudents    != null)
                statStudents.setText(count(c,
                    "SELECT COUNT(*) FROM student_profiles"));
            if (statExams       != null)
                statExams.setText(count(c,
                    "SELECT COUNT(*) FROM exams WHERE is_active = TRUE"));
            if (statQuestions   != null)
                statQuestions.setText(count(c,
                    "SELECT COUNT(*) FROM questions"));
            if (statResults     != null)
                statResults.setText(count(c,
                    "SELECT COUNT(*) FROM results"));
            if (statMalpractice != null)
                statMalpractice.setText(count(c,
                    "SELECT COUNT(*) FROM exam_attempts " +
                    "WHERE status = 'MALPRACTICE'"));

            StringBuilder sb = new StringBuilder();
            sb.append("KNOWLEDGE LAND COLLEGE - CBT ANALYTICS\n");
            sb.append("=======================================\n\n");

            // Pass rate per subject
            sb.append("Pass Rate by Subject:\n");
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT s.subject_code, " +
                    "COUNT(r.id) AS total, " +
                    "SUM(CASE WHEN r.percentage >= 40 THEN 1 ELSE 0 END) AS passed " +
                    "FROM results r " +
                    "JOIN exams e ON e.id = r.exam_id " +
                    "JOIN subjects s ON s.id = e.subject_id " +
                    "GROUP BY s.subject_code " +
                    "ORDER BY s.subject_code")) {

                ResultSet rs = ps.executeQuery();
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    int total  = rs.getInt(2);
                    int passed = rs.getInt(3);
                    double rate = (total == 0) ? 0 : passed * 100.0 / total;
                    sb.append(String.format("  %-12s : %d / %d passed (%.1f%%)\n",
                        rs.getString(1), passed, total, rate));
                }
                if (!any) sb.append("  No results yet.\n");
            }

            sb.append("\nHardest Questions (lowest correct rate):\n");
            // FIX: Removed PostgreSQL-specific syntax (::float, COUNT(aa.*))
            // Using standard SQL compatible with both H2 and PostgreSQL
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT q.question_text, s.subject_code, " +
                    "COUNT(aa.id) AS attempts, " +
                    "SUM(CASE WHEN aa.selected_option = o.option_label " +
                    "         THEN 1 ELSE 0 END) AS correct_count " +
                    "FROM attempt_answers aa " +
                    "JOIN questions q ON q.id = aa.question_id " +
                    "JOIN subjects s ON s.id = q.subject_id " +
                    "LEFT JOIN question_options o " +
                    "       ON o.question_id = q.id AND o.is_correct = TRUE " +
                    "GROUP BY q.id, q.question_text, s.subject_code " +
                    "HAVING COUNT(aa.id) >= 1 " +
                    "ORDER BY " +
                    "  CASE WHEN COUNT(aa.id) = 0 THEN 0 " +
                    "       ELSE CAST(SUM(CASE WHEN aa.selected_option = " +
                    "            o.option_label THEN 1 ELSE 0 END) AS FLOAT)" +
                    "            / COUNT(aa.id) END ASC " +
                    "LIMIT 10")) {

                ResultSet rs  = ps.executeQuery();
                int       n   = 1;
                boolean   any = false;
                while (rs.next()) {
                    any = true;
                    int    att = rs.getInt(3);
                    int    cor = rs.getInt(4);
                    double pct = (att == 0) ? 0 : cor * 100.0 / att;
                    String qt  = rs.getString(1);
                    if (qt != null && qt.length() > 90)
                        qt = qt.substring(0, 90) + "...";
                    sb.append(String.format(
                        "  %d. [%s] %.0f%% correct (%d/%d) - %s\n",
                        n++, rs.getString(2), pct, cor, att, qt));
                }
                if (!any)
                    sb.append("  No exam data yet. " +
                              "Take some exams first.\n");
            }

            sb.append("\nMore analytics:\n");
            sb.append("  - Item Analysis: Question Bank -> filter by topic\n");
            sb.append("  - Broadsheet: Results -> Export Excel\n");
            sb.append("  - Teacher workload: Question Bank -> filter by Author\n");

            if (reportArea != null) reportArea.setText(sb.toString());

        } catch (Exception e) {
            if (reportArea != null)
                reportArea.setText("Analytics error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String count(Connection c, String sql) {
        try (PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return String.valueOf(rs.getInt(1));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "0";
    }
}
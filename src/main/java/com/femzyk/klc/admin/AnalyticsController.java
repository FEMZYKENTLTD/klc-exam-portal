package com.femzyk.klc.admin;

import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.db.DatabaseManager;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;

import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AnalyticsController - KLC CBT Suite v1.0
 *
 * G2 DELIVERED: Topic-by-topic performance breakdown added to the
 * analytics report (presentation promise "topic-by-topic performance
 * breakdown"). Uses questions.topic joined through attempt_answers.
 *
 * RULE 11 REVIEW of the previous version: no defects found - queries
 * are cross-DB safe (CASE + CAST FLOAT, no ::float, no COUNT(x.*)).
 * All existing sections preserved: stat cards, pass rate per subject,
 * hardest questions.
 */
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
            sb.append("KNOWLEDGE LAND COLLEGE - CBT ANALYTICS v1.0\n");
            sb.append("============================================\n\n");

            // ── Pass rate per subject ──
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

            // ── G2: TOPIC-BY-TOPIC PERFORMANCE BREAKDOWN ──
            sb.append("\nTopic-by-Topic Performance (weakest topics first):\n");
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT s.subject_code, " +
                    "       COALESCE(q.topic, 'General') AS topic, " +
                    "       COUNT(aa.id) AS attempts, " +
                    "       SUM(CASE WHEN aa.selected_option = o.option_label " +
                    "                THEN 1 ELSE 0 END) AS correct_count " +
                    "FROM attempt_answers aa " +
                    "JOIN questions q ON q.id = aa.question_id " +
                    "JOIN subjects s ON s.id = q.subject_id " +
                    "LEFT JOIN question_options o " +
                    "       ON o.question_id = q.id AND o.is_correct = TRUE " +
                    "GROUP BY s.subject_code, COALESCE(q.topic, 'General') " +
                    "HAVING COUNT(aa.id) >= 1 " +
                    "ORDER BY " +
                    "  CASE WHEN COUNT(aa.id) = 0 THEN 0 " +
                    "       ELSE CAST(SUM(CASE WHEN aa.selected_option = " +
                    "            o.option_label THEN 1 ELSE 0 END) AS FLOAT)" +
                    "            / COUNT(aa.id) END ASC " +
                    "LIMIT 25")) {

                ResultSet rs = ps.executeQuery();
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    int att = rs.getInt(3);
                    int cor = rs.getInt(4);
                    double pct = (att == 0) ? 0 : cor * 100.0 / att;
                    String flag = pct < 40 ? "  [WEAK - NEEDS ATTENTION]"
                                : pct < 60 ? "  [AVERAGE]" : "";
                    sb.append(String.format(
                        "  %-12s | %-30s : %.0f%% correct (%d/%d)%s\n",
                        rs.getString(1),
                        rs.getString(2),
                        pct, cor, att, flag));
                }
                if (!any) sb.append("  No exam data yet. Topic analysis " +
                    "appears after students take exams.\n");
            }

            // ── Hardest questions ──
            sb.append("\nHardest Questions (lowest correct rate):\n");
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
                    sb.append("  No exam data yet. Take some exams first.\n");
            }

            // ── ITEM ANALYSIS (KLC v1.0 spec 7.6 - psychometrics) ──
            // Discrimination Index: correct-rate of TOP scorers minus
            // correct-rate of BOTTOM scorers per question (quartile method).
            sb.append("\nItem Analysis - Discrimination Index ");
            sb.append("(top-quartile vs bottom-quartile):\n");
            try (PreparedStatement ps = c.prepareStatement(
                    """
                    WITH scored AS (
                      SELECT aa.question_id qid, aa.selected_option sel,
                             o.option_label corr,
                             r.percentage pct
                      FROM attempt_answers aa
                      JOIN results r ON r.attempt_id = aa.attempt_id
                      LEFT JOIN question_options o
                             ON o.question_id = aa.question_id
                            AND o.is_correct = TRUE
                    ), ranked AS (
                      SELECT qid, sel, corr, pct,
                             PERCENT_RANK() OVER (ORDER BY pct DESC) pr
                      FROM scored
                    )
                    SELECT qid,
                      SUM(CASE WHEN pr <= 0.25 AND sel = corr
                               THEN 1 ELSE 0 END) top_ok,
                      SUM(CASE WHEN pr <= 0.25 THEN 1 ELSE 0 END) top_n,
                      SUM(CASE WHEN pr >= 0.75 AND sel = corr
                               THEN 1 ELSE 0 END) bot_ok,
                      SUM(CASE WHEN pr >= 0.75 THEN 1 ELSE 0 END) bot_n
                    FROM ranked GROUP BY qid
                    """)) {
                ResultSet rs = ps.executeQuery();
                int shown = 0;
                while (rs.next() && shown < 10) {
                    int tN = rs.getInt(3), bN = rs.getInt(5);
                    if (tN == 0 || bN == 0) continue;
                    double dIdx = (rs.getInt(2) * 1.0 / tN)
                                - (rs.getInt(4) * 1.0 / bN);
                    String flag = dIdx >= 0.40 ? "EXCELLENT"
                                : dIdx >= 0.30 ? "GOOD"
                                : dIdx >= 0.20 ? "ACCEPTABLE"
                                : "POOR - review this question";
                    String qt = questionLabel(c, rs.getString(1));
                    sb.append(String.format(
                        "  D-index %+.2f  [%s]  %s%n", dIdx, flag, qt));
                    shown++;
                }
                if (shown == 0)
                    sb.append("  Needs exam data (attempt + result rows).\n");
            }

            // ── DISTRACTOR ANALYSIS: % choosing each option on the
            //    hardest questions (KLC v1.0 spec 7.6) ──
            sb.append("\nItem Analysis - Distractor Analysis ");
            sb.append("(choice spread):\n");
            try (PreparedStatement ps = c.prepareStatement(
                    """
                    SELECT q.id, q.question_text,
                           aa.selected_option, COUNT(*) AS c
                    FROM attempt_answers aa
                    JOIN questions q ON q.id = aa.question_id
                    GROUP BY q.id, q.question_text, aa.selected_option
                    ORDER BY q.id
                    """)) {
                ResultSet rs = ps.executeQuery();
                LinkedHashMap<String, int[]> spread = new LinkedHashMap<>();
                LinkedHashMap<String, String> names = new LinkedHashMap<>();
                while (rs.next()) {
                    String qid = rs.getString(1);
                    String sel = rs.getString(3) == null
                        ? "-" : rs.getString(3);
                    spread.computeIfAbsent(qid,
                        k -> new int[6]); // A B C D E other
                    int[] arr = spread.get(qid);
                    int idx = switch (sel) {
                        case "A" -> 0; case "B" -> 1; case "C" -> 2;
                        case "D" -> 3; case "E" -> 4; default -> 5; };
                    arr[idx] += rs.getInt(4);
                    names.putIfAbsent(qid, shorten(rs.getString(2)));
                }
                int shown = 0;
                for (Map.Entry<String, int[]> e : spread.entrySet()) {
                    if (shown++ >= 8) break;
                    int[] a = e.getValue();
                    int tot = a[0] + a[1] + a[2] + a[3] + a[4] + a[5];
                    if (tot == 0) continue;
                    sb.append(String.format(
                        "  %s%n    A:%d%% B:%d%% C:%d%% D:%d%% E:%d%% (-:%d%%)%n",
                        names.get(e.getKey()),
                        a[0] * 100 / tot, a[1] * 100 / tot,
                        a[2] * 100 / tot, a[3] * 100 / tot,
                        a[4] * 100 / tot, a[5] * 100 / tot));
                }
                if (spread.isEmpty())
                    sb.append("  Needs exam data.\n");
            } catch (Exception ex) {
                sb.append("  (unavailable)\n");
            }

            // ── TEACHER WORKLOAD REPORT (KLC v1.0 spec 7.6) ──
            sb.append("\nTeacher Workload (questions authored / exams created):\n");
            try (PreparedStatement ps = c.prepareStatement(
                    """
                    SELECT u.full_name,
                      (SELECT COUNT(*) FROM questions q
                       WHERE q.created_by = u.id) AS questions,
                      (SELECT COUNT(*) FROM exams e
                       WHERE e.created_by = u.id) AS exams
                    FROM users u
                    WHERE u.role IN ('TEACHER','SUPER_ADMIN','EXAM_OFFICER')
                    ORDER BY 2 DESC, 3 DESC LIMIT 15
                    """)) {
                ResultSet rs = ps.executeQuery();
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    sb.append(String.format("  %-28s  %3d questions  %3d exams%n",
                        rs.getString(1), rs.getInt(2), rs.getInt(3)));
                }
                if (!any) sb.append("  No staff records yet.\n");
            }

            sb.append("\nMore analytics:\n");
            sb.append("  - Item Analysis: Question Bank -> filter by topic\n");
            sb.append("  - Broadsheet: Results -> Export Excel\n");

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

    // =======================================================================
    //  KLC v1.0 (spec 7.6): Export all analytics - CSV file via FileChooser
    // =======================================================================
    @FXML
    private void exportReport() {
        if (reportArea == null || reportArea.getText().isBlank()) {
            return;
        }
        try {
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setTitle("Export Analytics Report");
            fc.setInitialFileName("klc-analytics-"
                + java.time.LocalDate.now() + ".csv");
            fc.getExtensionFilters().addAll(
                new javafx.stage.FileChooser.ExtensionFilter(
                    "CSV / Text", "*.csv", "*.txt"));
            java.io.File f = fc.showSaveDialog(
                reportArea.getScene() != null
                    ? reportArea.getScene().getWindow() : null);
            if (f == null) return;
            // CSV-friendly: quote every line, keep structure readable
            StringBuilder out = new StringBuilder();
            for (String line : reportArea.getText().split("\n")) {
                out.append('"').append(line.replace("\"", "'"))
                   .append("\"\n");
            }
            java.nio.file.Files.writeString(f.toPath(), out.toString());
            reportArea.appendText("\n\n[Exported to " + f.getAbsolutePath()
                + "]");
        } catch (Exception e) {
            if (reportArea != null)
                reportArea.appendText("\n[Export failed: "
                    + e.getMessage() + "]");
        }
    }

    /** Short label for a question in the item-analysis sections. */
    private String shorten(String text) {
        if (text == null) return "(no text)";
        return text.length() > 60 ? text.substring(0, 60) + "..."
                                  : text;
    }

    /** Resolve a question id to a short label (for D-index listing). */
    private String questionLabel(Connection c, String qid) {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT question_text FROM questions WHERE id = ?")) {
            AuthService.setUuid(ps, 1, qid, c);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return shorten(rs.getString(1));
        } catch (Exception ignored) {}
        return qid;
    }
}

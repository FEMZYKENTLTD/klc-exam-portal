package com.femzyk.klc.student;

import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.db.DatabaseManager;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

import java.sql.*;

/**
 * ParentPortalController - KLC CBT SUITE v1.0 (Parent Portal)
 *
 * READ-ONLY view of a parent's ward results:
 *  - Parent links to the ward via the ward's admission number
 *    (captured at registration into parent_profiles.ward_admission_no)
 *  - Shows ward identity + every PUBLISHED result (subject, class,
 *    term, session, score, percentage)
 *  - No editing, no exam starting, no social module: parents see results.
 *
 * Rule 11: setUuid for UUID binds, cross-DB-safe SQL (no ::casts),
 * failures surfaced on the status label - never a blank screen.
 */
public class ParentPortalController {

    @FXML private Label wardLabel;
    @FXML private Label statusLabel;
    @FXML private TableView<ResultRow> resultTable;
    @FXML private TableColumn<ResultRow, String> colDate, colSubject,
        colClass, colTerm, colSession, colScore, colPercent;

    private final ObservableList<ResultRow> rows =
        FXCollections.observableArrayList();

    /** Read-only result row. */
    public static class ResultRow {
        String date, subject, clazz, term, session, score, percent;
        ResultRow(String d, String s, String c, String t,
                  String se, String sc, String p) {
            date = d; subject = s; clazz = c; term = t;
            session = se; score = sc; percent = p;
        }
        public String getDate()    { return date; }
        public String getSubject() { return subject; }
        public String getClazz()   { return clazz; }
        public String getTerm()    { return term; }
        public String getSession() { return session; }
        public String getScore()   { return score; }
        public String getPercent() { return percent; }
    }

    @FXML
    public void initialize() {
        colDate.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getDate()));
        colSubject.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getSubject()));
        colClass.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getClazz()));
        colTerm.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getTerm()));
        colSession.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getSession()));
        colScore.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getScore()));
        colPercent.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getPercent()));
        resultTable.setItems(rows);
        load();
    }

    @FXML
    private void refresh() {
        load();
    }

    private void load() {
        rows.clear();
        try (Connection c = DatabaseManager.getConnection()) {

            // Ward identity (if the admission number matches a student)
            String wardAdm = null;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT ward_admission_no FROM parent_profiles " +
                    "WHERE user_id = ?")) {
                AuthService.setUuid(ps, 1, AuthService.Session.userId, c);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) wardAdm = rs.getString(1);
            }
            if (wardAdm == null || wardAdm.isBlank()) {
                if (wardLabel != null)
                    wardLabel.setText("No ward linked yet.");
                if (statusLabel != null)
                    statusLabel.setText("Your registration is missing the "
                        + "ward's admission number. Re-register or contact "
                        + "the school office.");
                return;
            }

            boolean wardFound = false;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT sp.surname, sp.other_names, sp.class_level, " +
                    "sp.arm FROM student_profiles sp " +
                    "WHERE sp.admission_no = ?")) {
                ps.setString(1, wardAdm);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    wardFound = true;
                    if (wardLabel != null) wardLabel.setText(
                        "Ward: " + rs.getString(1) + " " + rs.getString(2)
                        + "  |  " + rs.getString(3)
                        + (rs.getString(4) == null || rs.getString(4).isBlank()
                            ? "" : " " + rs.getString(4))
                        + "  |  " + wardAdm);
                }
            }
            if (!wardFound) {
                if (wardLabel != null)
                    wardLabel.setText("No ward linked yet.");
                if (statusLabel != null)
                    statusLabel.setText("Admission number '" + wardAdm
                        + "' was not found. Check the spelling or contact "
                        + "the school office.");
                return;
            }

            // Published results only
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT r.created_at, s.subject_code, e.class_level, " +
                    "COALESCE(e.term,'-'), COALESCE(e.session,'-'), " +
                    "r.score, r.total_questions, r.percentage " +
                    "FROM results r " +
                    "JOIN exams e ON e.id = r.exam_id " +
                    "JOIN subjects s ON s.id = e.subject_id " +
                    "JOIN student_profiles sp ON sp.user_id = r.student_id " +
                    "WHERE sp.admission_no = ? " +
                    "AND COALESCE(r.published, TRUE) = TRUE " +
                    "ORDER BY r.created_at DESC")) {
                ps.setString(1, wardAdm);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp(1);
                    rows.add(new ResultRow(
                        ts == null ? "-" :
                            ts.toLocalDateTime().toLocalDate().toString(),
                        rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5),
                        rs.getBigDecimal(6) + " / " + rs.getInt(7),
                        String.format("%.1f%%",
                            rs.getBigDecimal(8) == null ? 0
                                : rs.getBigDecimal(8).doubleValue())));
                }
            }
            if (statusLabel != null)
                statusLabel.setText(rows.isEmpty()
                    ? "No published results yet for this ward."
                    : rows.size() + " result(s) loaded. Read-only view.");

        } catch (Exception e) {
            if (statusLabel != null)
                statusLabel.setText("Load error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @FXML
    private void logout() {
        try {
            AuthService.Session.clear();
            com.femzyk.klc.MainApp.setRoot("login.fxml", null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

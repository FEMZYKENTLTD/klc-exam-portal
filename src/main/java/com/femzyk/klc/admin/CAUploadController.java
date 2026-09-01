package com.femzyk.klc.admin;

import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.db.DatabaseManager;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * CAUploadController v1.0
 *
 * FIXES (this file replaces the broken "ESOLUTION" version):
 * 1. HANDLER NAMES RESTORED to match ca_scores.fxml exactly:
 *    #uploadCAFile, #saveManualEntry, #refreshTable.
 *    (The broken version had chooseFile()/saveManualCA() which caused
 *    "Error resolving onAction='#uploadCAFile'" - page failed to load.)
 * 2. REMOVED phantom column recorded_by - it does NOT exist in the
 *    ca_scores schema. Inserts now match the real table exactly.
 * 3. ON CONFLICT now matches the REAL unique constraint:
 *    (student_id, subject_id, term, session) - the broken 3-column
 *    version would throw an SQL error on PostgreSQL.
 * 4. class_level + session are stored again (the broken version
 *    dropped them). Session is read from school_profile.session_current
 *    with a safe fallback, never hardcoded.
 * 5. CSV column order matches what the FXML tells the user:
 *    admission_no, subject_name, ca1_score, ca2_score, term, session
 * 6. CROSS-DB UPSERT: H2 (MODE=PostgreSQL) does not reliably support
 *    ON CONFLICT DO UPDATE, so the upsert is implemented as
 *    check-then-UPDATE-or-INSERT, which works identically on both
 *    databases. All UUID binds use AuthService.setUuid (Rule 2).
 * 7. ROLE ENFORCEMENT (permission matrix): CA entry allowed for
 *    SUPER_ADMIN, PRINCIPAL_ADMIN, EXAM_OFFICER, TEACHER. Students
 *    are refused.
 */
public class CAUploadController {

    @FXML private Label statusLabel;
    @FXML private ComboBox<String> manualStudentBox;
    @FXML private ComboBox<String> manualSubjectBox;
    @FXML private ComboBox<String> manualTermBox;
    @FXML private TextField manualCA1Field;
    @FXML private TextField manualCA2Field;
    @FXML private Label manualStatusLabel;
    @FXML private TableView<CARow> caTable;
    @FXML private TableColumn<CARow, String> colStudent, colSubject,
                                             colClass, colTerm,
                                             colCA1, colCA2, colTotal;

    private final ObservableList<CARow> caData =
        FXCollections.observableArrayList();

    private final Map<String, String> studentIdMap    = new HashMap<>();
    private final Map<String, String> studentClassMap = new HashMap<>();
    private final Map<String, String> subjectIdMap    = new HashMap<>();

    public static class CARow {
        String studentName, subject, classLevel, term, ca1, ca2, total;

        CARow(String sn, String sub, String cl, String t,
              String c1, String c2, String tot) {
            studentName = sn; subject = sub; classLevel = cl;
            term = t; ca1 = c1; ca2 = c2; total = tot;
        }

        public String getStudent()    { return studentName; }
        public String getSubject()    { return subject; }
        public String getClassLevel() { return classLevel; }
        public String getTerm()       { return term; }
        public String getCA1()        { return ca1; }
        public String getCA2()        { return ca2; }
        public String getTotal()      { return total; }
    }

    private boolean canEnterCA() {
        String r = AuthService.Session.role;
        return "SUPER_ADMIN".equals(r) || "PRINCIPAL_ADMIN".equals(r)
            || "EXAM_OFFICER".equals(r) || "TEACHER".equals(r);
    }

    @FXML
    public void initialize() {
        setupTable();
        loadStudents();
        loadSubjects();
        if (manualTermBox != null)
            manualTermBox.getItems().addAll("1st","2nd","3rd");
        loadCAScores();
    }

    private void setupTable() {
        if (colStudent != null)
            colStudent.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(
                    c.getValue().getStudent()));
        if (colSubject != null)
            colSubject.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(
                    c.getValue().getSubject()));
        if (colClass != null)
            colClass.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(
                    c.getValue().getClassLevel()));
        if (colTerm != null)
            colTerm.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(
                    c.getValue().getTerm()));
        if (colCA1 != null)
            colCA1.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(
                    c.getValue().getCA1()));
        if (colCA2 != null)
            colCA2.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(
                    c.getValue().getCA2()));
        if (colTotal != null)
            colTotal.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(
                    c.getValue().getTotal()));
        if (caTable != null)
            caTable.setItems(caData);
    }

    private void loadStudents() {
        if (manualStudentBox == null) return;
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT u.id, u.full_name, sp.admission_no, sp.class_level " +
                 "FROM users u " +
                 "JOIN student_profiles sp ON sp.user_id = u.id " +
                 "WHERE u.role = 'STUDENT' AND u.is_active = TRUE " +
                 "ORDER BY sp.class_level, u.full_name")) {

            ResultSet rs = ps.executeQuery();
            manualStudentBox.getItems().clear();
            studentIdMap.clear();
            studentClassMap.clear();

            while (rs.next()) {
                String display = rs.getString("full_name") +
                    " (" + rs.getString("admission_no") + ")";
                manualStudentBox.getItems().add(display);
                studentIdMap.put(display, rs.getString("id"));
                studentClassMap.put(display, rs.getString("class_level"));
            }
        } catch (Exception e) {
            setManualStatus("Error loading students: " + e.getMessage(), true);
        }
    }

    private void loadSubjects() {
        if (manualSubjectBox == null) return;
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT MIN(CAST(id AS VARCHAR(36))) AS id, subject_name " +
                 "FROM subjects " +
                 "WHERE is_active = TRUE " +
                 "GROUP BY subject_name ORDER BY subject_name")) {

            ResultSet rs = ps.executeQuery();
            manualSubjectBox.getItems().clear();
            subjectIdMap.clear();

            while (rs.next()) {
                String name = rs.getString("subject_name");
                manualSubjectBox.getItems().add(name);
                subjectIdMap.put(name, rs.getString("id"));
            }
        } catch (Exception e) {
            setManualStatus("Error loading subjects: " + e.getMessage(), true);
        }
    }

    /** Current academic session from school_profile, safe fallback. */
    private String currentSession(Connection c) {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT session_current FROM school_profile LIMIT 1")) {
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String s = rs.getString(1);
                if (s != null && !s.isBlank()) return s;
            }
        } catch (Exception ignored) {}
        return "2024/2025";
    }

    /**
     * Cross-DB upsert: UPDATE first, INSERT if no row was updated.
     * Works identically on H2 and PostgreSQL (no ON CONFLICT needed)
     * and matches the real UNIQUE(student_id, subject_id, term, session).
     */
    private void upsertCaScore(Connection c, String studentId,
                               String subjectId, String classLevel,
                               String term, String session,
                               double ca1, double ca2) throws Exception {

        int updated;
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE ca_scores SET ca1_score=?, ca2_score=?, class_level=? " +
                "WHERE student_id=? AND subject_id=? AND term=? AND session=?")) {
            ps.setDouble(1, ca1);
            ps.setDouble(2, ca2);
            ps.setString(3, classLevel);
            AuthService.setUuid(ps, 4, studentId, c);
            AuthService.setUuid(ps, 5, subjectId, c);
            ps.setString(6, term);
            ps.setString(7, session);
            updated = ps.executeUpdate();
        }

        if (updated == 0) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO ca_scores(" +
                    "id, student_id, subject_id, class_level, " +
                    "term, session, ca1_score, ca2_score) " +
                    "VALUES(?,?,?,?,?,?,?,?)")) {
                AuthService.setUuid(ps, 1, UUID.randomUUID().toString(), c);
                AuthService.setUuid(ps, 2, studentId, c);
                AuthService.setUuid(ps, 3, subjectId, c);
                ps.setString(4, classLevel);
                ps.setString(5, term);
                ps.setString(6, session);
                ps.setDouble(7, ca1);
                ps.setDouble(8, ca2);
                ps.executeUpdate();
            }
        }
    }

    // =========================================================================
    //  MANUAL ENTRY - onAction="#saveManualEntry" in ca_scores.fxml
    // =========================================================================
    @FXML
    private void saveManualEntry() {
        if (!canEnterCA()) {
            setManualStatus("Access denied - staff only.", true);
            return;
        }

        String stDisplay = manualStudentBox == null
            ? null : manualStudentBox.getValue();
        String subName = manualSubjectBox == null
            ? null : manualSubjectBox.getValue();
        String term = manualTermBox == null
            ? null : manualTermBox.getValue();

        if (stDisplay == null || subName == null || term == null) {
            setManualStatus("Select Student, Subject and Term", true);
            return;
        }

        String ca1Str = manualCA1Field == null
            ? "" : manualCA1Field.getText().trim();
        String ca2Str = manualCA2Field == null
            ? "" : manualCA2Field.getText().trim();

        if (ca1Str.isBlank() || ca2Str.isBlank()) {
            setManualStatus("Enter both CA1 and CA2 scores (0 - 20)", true);
            return;
        }

        double ca1, ca2;
        try {
            ca1 = Double.parseDouble(ca1Str);
            ca2 = Double.parseDouble(ca2Str);
        } catch (NumberFormatException e) {
            setManualStatus("Scores must be numbers (0 - 20)", true);
            return;
        }
        if (ca1 < 0 || ca1 > 20 || ca2 < 0 || ca2 > 20) {
            setManualStatus("CA1 and CA2 scores must be between 0 and 20", true);
            return;
        }

        String sid   = studentIdMap.get(stDisplay);
        String subId = subjectIdMap.get(subName);
        String cls   = studentClassMap.getOrDefault(stDisplay, null);

        if (sid == null || subId == null) {
            setManualStatus("Invalid student or subject selection.", true);
            return;
        }

        try (Connection c = DatabaseManager.getConnection()) {
            String session = currentSession(c);
            upsertCaScore(c, sid, subId, cls, term, session, ca1, ca2);

            AuthService.logAudit("CA_MANUAL_ENTRY", "ca_scores", sid);
            setManualStatus("Saved CA for " +
                stDisplay.split("\\(")[0].trim() + " - " + subName +
                " - " + term + " Term (Total: " +
                String.format("%.1f", ca1 + ca2) + "/40)", false);
            if (manualCA1Field != null) manualCA1Field.clear();
            if (manualCA2Field != null) manualCA2Field.clear();
            loadCAScores();

        } catch (Exception e) {
            setManualStatus("Error: " + e.getMessage(), true);
        }
    }

    // =========================================================================
    //  CSV UPLOAD - onAction="#uploadCAFile" in ca_scores.fxml
    //  CSV: admission_no, subject_name, ca1_score, ca2_score, term, session
    // =========================================================================
    @FXML
    private void uploadCAFile() {
        if (!canEnterCA()) {
            setStatus("Access denied - staff only.", true);
            return;
        }

        FileChooser fc = new FileChooser();
        fc.setTitle("Select CA Scores CSV");
        fc.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("CSV Files", "*.csv"));
        File f = fc.showOpenDialog(
            caTable != null ? caTable.getScene().getWindow() : null);
        if (f == null) return;

        int count = 0, skipped = 0;
        try (Connection c = DatabaseManager.getConnection();
             BufferedReader br = new BufferedReader(new FileReader(f))) {

            String defaultSession = currentSession(c);
            String line;
            boolean first = true;

            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;

                // Skip a header row if present
                if (first) {
                    first = false;
                    String low = line.toLowerCase();
                    if (low.contains("admission") || low.contains("subject"))
                        continue;
                }

                String[] p = line.split(",");
                if (p.length < 5) { skipped++; continue; }

                String admNo   = p[0].trim();
                String subName = p[1].trim();
                double ca1, ca2;
                try {
                    ca1 = Double.parseDouble(p[2].trim());
                    ca2 = Double.parseDouble(p[3].trim());
                } catch (NumberFormatException e) {
                    skipped++; continue;
                }
                if (ca1 < 0 || ca1 > 20 || ca2 < 0 || ca2 > 20) {
                    skipped++; continue;
                }
                String term    = p[4].trim();
                String session = p.length >= 6 && !p[5].trim().isBlank()
                               ? p[5].trim() : defaultSession;

                // Resolve student
                String sid = null, cls = null;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT user_id, class_level FROM student_profiles " +
                        "WHERE admission_no=?")) {
                    ps.setString(1, admNo);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        sid = rs.getString(1);
                        cls = rs.getString(2);
                    }
                }
                if (sid == null) { skipped++; continue; }

                // Resolve subject (case-insensitive)
                String subId = null;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT MIN(CAST(id AS VARCHAR(36))) FROM subjects " +
                        "WHERE UPPER(subject_name)=UPPER(?) " +
                        "AND is_active = TRUE")) {
                    ps.setString(1, subName);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) subId = rs.getString(1);
                }
                if (subId == null) { skipped++; continue; }

                upsertCaScore(c, sid, subId, cls, term, session, ca1, ca2);
                count++;
            }

            AuthService.logAudit("CA_CSV_UPLOAD", "ca_scores", null);
            setStatus(count + " CA records uploaded." +
                (skipped > 0 ? " " + skipped + " rows skipped." : ""), false);
            loadCAScores();

        } catch (Exception e) {
            setStatus("CSV error: " + e.getMessage(), true);
        }
    }

    // =========================================================================
    //  REFRESH - onAction="#refreshTable" in ca_scores.fxml
    // =========================================================================
    @FXML
    private void refreshTable() { loadCAScores(); }

    // =========================================================================
    //  LOAD CA SCORES TABLE WITH STUDENT NAME JOIN
    // =========================================================================
    public void loadCAScores() {
        caData.clear();
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT COALESCE(u.full_name, 'Unknown') AS student_name, " +
                 "       s.subject_name, " +
                 "       COALESCE(ca.class_level, s.class_level) AS cls, " +
                 "       ca.term, ca.ca1_score, ca.ca2_score " +
                 "FROM ca_scores ca " +
                 "LEFT JOIN users u ON u.id = ca.student_id " +
                 "JOIN subjects s ON s.id = ca.subject_id " +
                 "ORDER BY s.subject_name, u.full_name " +
                 "LIMIT 500")) {

            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                double c1 = rs.getDouble("ca1_score");
                double c2 = rs.getDouble("ca2_score");
                caData.add(new CARow(
                    rs.getString("student_name"),
                    rs.getString("subject_name"),
                    rs.getString("cls"),
                    rs.getString("term"),
                    String.format("%.1f", c1),
                    String.format("%.1f", c2),
                    String.format("%.1f", c1 + c2)
                ));
            }
            setStatus("Loaded " + caData.size() + " CA records", false);
        } catch (Exception e) {
            setStatus("Error loading CA table: " + e.getMessage(), true);
        }
    }

    private void setStatus(String m, boolean err) {
        if (statusLabel == null) return;
        statusLabel.setText(m);
        statusLabel.setStyle(err
            ? "-fx-text-fill:#ef4444; -fx-font-weight:bold;"
            : "-fx-text-fill:#0f7a3a; -fx-font-weight:bold;");
    }

    private void setManualStatus(String m, boolean err) {
        if (manualStatusLabel == null) return;
        manualStatusLabel.setText(m);
        manualStatusLabel.setStyle(err
            ? "-fx-text-fill:#ef4444; -fx-font-weight:bold;"
            : "-fx-text-fill:#0f7a3a; -fx-font-weight:bold;");
    }
}

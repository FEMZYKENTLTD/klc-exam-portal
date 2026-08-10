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

public class CAUploadController {

    // ─── CSV Upload section ───────────────────────────────────────────────────
    @FXML private Label statusLabel;

    // ─── Manual Entry section ─────────────────────────────────────────────────
    @FXML private ComboBox<String>  manualStudentBox;
    @FXML private ComboBox<String>  manualSubjectBox;
    @FXML private ComboBox<String>  manualTermBox;
    @FXML private TextField         manualCA1Field;
    @FXML private TextField         manualCA2Field;
    @FXML private Label             manualStatusLabel;

    // ─── CA Scores Table ──────────────────────────────────────────────────────
    @FXML private TableView<CARow>              caTable;
    @FXML private TableColumn<CARow, String>    colStudent, colSubject,
                                                 colClass, colTerm,
                                                 colCA1, colCA2, colTotal;

    private final ObservableList<CARow> caData = FXCollections.observableArrayList();

    // Maps for manual entry
    private final Map<String, String> studentIdMap  = new HashMap<>();
    private final Map<String, String> subjectIdMap  = new HashMap<>();
    private final Map<String, String> subjectClassMap = new HashMap<>();

    // ─── Inner Model ──────────────────────────────────────────────────────────
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

    // =========================================================================
    //  INITIALIZE
    // =========================================================================
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
                 "SELECT u.id, u.full_name, sp.admission_no " +
                 "FROM users u " +
                 "JOIN student_profiles sp ON sp.user_id = u.id " +
                 "WHERE u.role = 'STUDENT' AND u.is_active = TRUE " +
                 "ORDER BY sp.class_level, u.full_name")) {
            ResultSet rs = ps.executeQuery();
            manualStudentBox.getItems().clear();
            studentIdMap.clear();
            while (rs.next()) {
                String display = rs.getString("full_name") +
                    " (" + rs.getString("admission_no") + ")";
                manualStudentBox.getItems().add(display);
                studentIdMap.put(display, rs.getString("id"));
            }
        } catch (Exception e) {
            setManualStatus("Error loading students: " + e.getMessage(), true);
        }
    }

    private void loadSubjects() {
        if (manualSubjectBox == null) return;
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT MIN(id) AS id, subject_name, class_level " +
                 "FROM subjects WHERE is_active = TRUE " +
                 "GROUP BY subject_name, class_level " +
                 "ORDER BY subject_name")) {
            ResultSet rs = ps.executeQuery();
            manualSubjectBox.getItems().clear();
            subjectIdMap.clear();
            subjectClassMap.clear();
            while (rs.next()) {
                String name  = rs.getString("subject_name");
                String cls   = rs.getString("class_level");
                String id    = rs.getString("id");
                String display = name + " (" + cls + ")";
                if (!manualSubjectBox.getItems().contains(display)) {
                    manualSubjectBox.getItems().add(display);
                    subjectIdMap.put(display, id);
                    subjectClassMap.put(display, cls);
                }
            }
        } catch (Exception e) {
            setManualStatus("Error loading subjects: " + e.getMessage(), true);
        }
    }

    private void loadCAScores() {
        if (caTable == null) return;
        caData.clear();
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT u.full_name, s.subject_name, cs.class_level, " +
                 "cs.term, cs.ca1_score, cs.ca2_score, " +
                 "COALESCE(cs.ca1_score,0) + COALESCE(cs.ca2_score,0) AS total " +
                 "FROM ca_scores cs " +
                 "JOIN users u ON u.id = cs.student_id " +
                 "JOIN subjects s ON s.id = cs.subject_id " +
                 "ORDER BY u.full_name, s.subject_name, cs.term " +
                 "LIMIT 500")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                caData.add(new CARow(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    String.format("%.1f", rs.getDouble(5)),
                    String.format("%.1f", rs.getDouble(6)),
                    String.format("%.1f", rs.getDouble(7))
                ));
            }
            setStatus("Loaded " + caData.size() + " CA records", false);
        } catch (Exception e) {
            setStatus("Error: " + e.getMessage(), true);
        }
    }

    // =========================================================================
    //  MANUAL ENTRY SAVE
    // =========================================================================
    @FXML
    private void saveManualEntry() {
        String studentDisplay = manualStudentBox == null
            ? null : manualStudentBox.getValue();
        String subjectDisplay = manualSubjectBox == null
            ? null : manualSubjectBox.getValue();
        String term = manualTermBox == null
            ? null : manualTermBox.getValue();
        String ca1Str = manualCA1Field == null
            ? "" : manualCA1Field.getText().trim();
        String ca2Str = manualCA2Field == null
            ? "" : manualCA2Field.getText().trim();

        if (studentDisplay == null || subjectDisplay == null || term == null) {
            setManualStatus("Please select Student, Subject and Term.", true);
            return;
        }
        if (ca1Str.isBlank() || ca2Str.isBlank()) {
            setManualStatus("Please enter CA1 and CA2 scores.", true);
            return;
        }

        double ca1, ca2;
        try {
            ca1 = Double.parseDouble(ca1Str);
            ca2 = Double.parseDouble(ca2Str);
        } catch (NumberFormatException e) {
            setManualStatus("CA1 and CA2 must be numbers.", true);
            return;
        }

        if (ca1 < 0 || ca1 > 20 || ca2 < 0 || ca2 > 20) {
            setManualStatus("CA scores must be between 0 and 20.", true);
            return;
        }

        String studentId = studentIdMap.get(studentDisplay);
        String subjectId = subjectIdMap.get(subjectDisplay);
        String classLevel = subjectClassMap.get(subjectDisplay);

        if (studentId == null || subjectId == null) {
            setManualStatus("Invalid student or subject selection.", true);
            return;
        }

        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO ca_scores(" +
                 "  id, student_id, subject_id, class_level, " +
                 "  term, session, ca1_score, ca2_score) " +
                 "VALUES(?,?,?,?,?,?,?,?) " +
                 "ON CONFLICT (student_id, subject_id, term, session) " +
                 "DO UPDATE SET " +
                 "  ca1_score = EXCLUDED.ca1_score," +
                 "  ca2_score = EXCLUDED.ca2_score")) {

            ps.setString(1, UUID.randomUUID().toString());
            AuthService.setUuid(ps, 2, studentId, c);
            AuthService.setUuid(ps, 3, subjectId, c);
            ps.setString(4, classLevel);
            ps.setString(5, term);
            ps.setString(6, "2024/2025");
            ps.setDouble(7, ca1);
            ps.setDouble(8, ca2);
            ps.executeUpdate();

            AuthService.logAudit("CA_MANUAL_ENTRY", "ca_scores", studentId);
            setManualStatus(
                "CA scores saved for " + studentDisplay.split("\\(")[0].trim() +
                " - " + subjectDisplay + " - " + term + " Term", false);

            // Clear fields
            if (manualCA1Field != null) manualCA1Field.clear();
            if (manualCA2Field != null) manualCA2Field.clear();

            loadCAScores();

        } catch (Exception e) {
            setManualStatus("Error: " + e.getMessage(), true);
        }
    }

    // =========================================================================
    //  CSV UPLOAD
    // =========================================================================
    @FXML
    private void uploadCAFile() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select CA Scores CSV File");
        fc.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("CSV Files","*.csv"));
        File file = fc.showOpenDialog(
            statusLabel != null ? statusLabel.getScene().getWindow() : null);
        if (file == null) return;

        try (BufferedReader br = new BufferedReader(new FileReader(file));
             Connection c = DatabaseManager.getConnection()) {

            String line;
            int count = 0, skipped = 0;

            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length < 6) { skipped++; continue; }

                String admission   = parts[0].trim();
                String subjectName = parts[1].trim();
                double ca1, ca2;
                try {
                    ca1 = Double.parseDouble(parts[2].trim());
                    ca2 = Double.parseDouble(parts[3].trim());
                } catch (NumberFormatException e) {
                    skipped++;
                    continue;
                }
                String term    = parts[4].trim();
                String session = parts[5].trim();

                String studentId = getStudentId(c, admission);
                String[] subjectInfo = getSubjectInfo(c, subjectName);

                if (studentId == null || subjectInfo == null) {
                    skipped++;
                    continue;
                }

                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO ca_scores(" +
                        "  id, student_id, subject_id, class_level, " +
                        "  term, session, ca1_score, ca2_score) " +
                        "VALUES(?,?,?,?,?,?,?,?) " +
                        "ON CONFLICT (student_id, subject_id, term, session) " +
                        "DO UPDATE SET " +
                        "  ca1_score = EXCLUDED.ca1_score," +
                        "  ca2_score = EXCLUDED.ca2_score")) {
                    ps.setString(1, UUID.randomUUID().toString());
                    AuthService.setUuid(ps, 2, studentId, c);
                    AuthService.setUuid(ps, 3, subjectInfo[0], c);
                    ps.setString(4, subjectInfo[1]);
                    ps.setString(5, term);
                    ps.setString(6, session);
                    ps.setDouble(7, ca1);
                    ps.setDouble(8, ca2);
                    ps.executeUpdate();
                    count++;
                }
            }

            setStatus(count + " CA records uploaded. " +
                (skipped > 0 ? skipped + " rows skipped." : ""), false);
            loadCAScores();

        } catch (Exception e) {
            setStatus("Error: " + e.getMessage(), true);
        }
    }

    private String getStudentId(Connection c, String admission)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT user_id FROM student_profiles " +
                "WHERE admission_no = ?")) {
            ps.setString(1, admission);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private String[] getSubjectInfo(Connection c, String subjectName)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT MIN(id), MIN(class_level) FROM subjects " +
                "WHERE subject_name = ? AND is_active = TRUE")) {
            ps.setString(1, subjectName);
            ResultSet rs = ps.executeQuery();
            if (rs.next() && rs.getString(1) != null)
                return new String[]{rs.getString(1), rs.getString(2)};
        }
        return null;
    }

    @FXML
    private void refreshTable() { loadCAScores(); }

    private void setStatus(String msg, boolean error) {
        if (statusLabel == null) return;
        statusLabel.setText(msg);
        statusLabel.setStyle(error
            ? "-fx-text-fill:#ef4444; -fx-font-weight:bold;"
            : "-fx-text-fill:#10b981; -fx-font-weight:bold;");
    }

    private void setManualStatus(String msg, boolean error) {
        if (manualStatusLabel == null) return;
        manualStatusLabel.setText(msg);
        manualStatusLabel.setStyle(error
            ? "-fx-text-fill:#ef4444; -fx-font-weight:bold;"
            : "-fx-text-fill:#10b981; -fx-font-weight:bold;");
    }
}
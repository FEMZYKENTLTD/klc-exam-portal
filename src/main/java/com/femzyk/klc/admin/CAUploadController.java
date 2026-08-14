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

    private final ObservableList<CARow> caData = FXCollections.observableArrayList();

    private final Map<String, String> studentIdMap    = new HashMap<>();
    private final Map<String, String> subjectIdMap    = new HashMap<>();
    private final Map<String, String> subjectClassMap = new HashMap<>();

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
                 "SELECT MIN(id) AS id, subject_name, MIN(class_level) AS cl " +
                 "FROM subjects " +
                 "WHERE is_active = TRUE " +
                 "GROUP BY subject_name ORDER BY subject_name")) {

            ResultSet rs = ps.executeQuery();
            manualSubjectBox.getItems().clear();
            subjectIdMap.clear();
            subjectClassMap.clear();

            while (rs.next()) {
                String name = rs.getString("subject_name");
                manualSubjectBox.getItems().add(name);
                subjectIdMap.put(name, rs.getString("id"));
                subjectClassMap.put(name, rs.getString("cl"));
            }
        } catch (Exception e) {
            setManualStatus("Error loading subjects: " + e.getMessage(), true);
        }
    }

    // =========================================================================
    //  MANUAL ENTRY SAVING WITH AUTO-TOTAL AND NUMERICAL VALIDATION
    // =========================================================================
    @FXML
    private void saveManualCA() {
        String stDisplay = manualStudentBox.getValue();
        String subName   = manualSubjectBox.getValue();
        String term      = manualTermBox.getValue();

        if (stDisplay == null || subName == null || term == null) {
            setManualStatus("Select Student, Subject and Term", true);
            return;
        }

        double c1 = 0, c2 = 0;
        try {
            c1 = manualCA1Field.getText().isBlank() ? 0
               : Double.parseDouble(manualCA1Field.getText().trim());
            c2 = manualCA2Field.getText().isBlank() ? 0
               : Double.parseDouble(manualCA2Field.getText().trim());

            if (c1 < 0 || c1 > 20 || c2 < 0 || c2 > 20) {
                setManualStatus("CA1 and CA2 scores must be between 0 and 20", true);
                return;
            }
        } catch (NumberFormatException e) {
            setManualStatus("Scores must be numbers (0 - 20)", true);
            return;
        }

        String sid   = studentIdMap.get(stDisplay);
        String subId = subjectIdMap.get(subName);

        try (Connection c = DatabaseManager.getConnection()) {
            String caId = UUID.randomUUID().toString();
            try (PreparedStatement check = c.prepareStatement(
                    "SELECT id FROM ca_scores " +
                    "WHERE student_id=? AND subject_id=? AND term=?")) {
                AuthService.setUuid(check, 1, sid, c);
                AuthService.setUuid(check, 2, subId, c);
                check.setString(3, term);
                ResultSet rs = check.executeQuery();
                if (rs.next()) caId = rs.getString(1);
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO ca_scores(" +
                    "id, student_id, subject_id, term, " +
                    "ca1_score, ca2_score, recorded_by) " +
                    "VALUES(?,?,?,?,?,?,?) " +
                    "ON CONFLICT(student_id, subject_id, term) " +
                    "DO UPDATE SET ca1_score=EXCLUDED.ca1_score, " +
                    "              ca2_score=EXCLUDED.ca2_score")) {
                AuthService.setUuid(ps, 1, caId, c);
                AuthService.setUuid(ps, 2, sid, c);
                AuthService.setUuid(ps, 3, subId, c);
                ps.setString(4, term);
                ps.setDouble(5, c1);
                ps.setDouble(6, c2);
                AuthService.setUuid(ps, 7, AuthService.Session.userId, c);
                ps.executeUpdate();
            }

            setManualStatus("Saved CA score for " + stDisplay +
                " (Total: " + (c1 + c2) + "/40)", false);
            manualCA1Field.clear();
            manualCA2Field.clear();
            loadCAScores();

        } catch (Exception e) {
            setManualStatus("Error: " + e.getMessage(), true);
        }
    }

    // =========================================================================
    //  LOAD CA SCORES TABLE WITH STUDENT NAME JOIN
    // =========================================================================
    @FXML
    public void loadCAScores() {
        caData.clear();
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT COALESCE(u.full_name, 'Unknown') AS student_name, " +
                 "       s.subject_name, s.class_level, " +
                 "       ca.term, ca.ca1_score, ca.ca2_score " +
                 "FROM ca_scores ca " +
                 "LEFT JOIN users u ON u.id = ca.student_id " +
                 "JOIN subjects s ON s.id = ca.subject_id " +
                 "ORDER BY s.subject_name, u.full_name")) {

            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                double c1 = rs.getDouble("ca1_score");
                double c2 = rs.getDouble("ca2_score");
                caData.add(new CARow(
                    rs.getString("student_name"),
                    rs.getString("subject_name"),
                    rs.getString("class_level"),
                    rs.getString("term"),
                    String.format("%.1f", c1),
                    String.format("%.1f", c2),
                    String.format("%.1f", c1 + c2)
                ));
            }
        } catch (Exception e) {
            if (statusLabel != null)
                statusLabel.setText("Error loading CA table: " + e.getMessage());
        }
    }

    @FXML
    private void chooseFile() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select CA Scores CSV");
        fc.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("CSV Files", "*.csv"));
        File f = fc.showOpenDialog(
            caTable != null ? caTable.getScene().getWindow() : null);

        if (f == null) return;
        try {
            int count = importCsv(f);
            setStatus("Successfully imported " + count + " CA score rows", false);
            loadCAScores();
        } catch (Exception e) {
            setStatus("CSV error: " + e.getMessage(), true);
        }
    }

    private int importCsv(File file) throws Exception {
        int count = 0;
        try (Connection c = DatabaseManager.getConnection();
             BufferedReader br = new BufferedReader(new FileReader(file))) {

            String line = br.readLine(); // skip header
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] p = line.split(",");
                if (p.length < 5) continue;

                String admNo   = p[0].trim();
                String subName = p[1].trim();
                String term    = p[2].trim();
                double c1      = Double.parseDouble(p[3].trim());
                double c2      = Double.parseDouble(p[4].trim());

                String sid = null;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT user_id FROM student_profiles " +
                        "WHERE admission_no=?")) {
                    ps.setString(1, admNo);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) sid = rs.getString(1);
                }
                if (sid == null) continue;

                String subId = null;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id FROM subjects " +
                        "WHERE subject_name=? LIMIT 1")) {
                    ps.setString(1, subName);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) subId = rs.getString(1);
                }
                if (subId == null) continue;

                String caId = UUID.randomUUID().toString();
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO ca_scores(" +
                        "id, student_id, subject_id, term, " +
                        "ca1_score, ca2_score, recorded_by) " +
                        "VALUES(?,?,?,?,?,?,?) " +
                        "ON CONFLICT(student_id, subject_id, term) " +
                        "DO UPDATE SET ca1_score=EXCLUDED.ca1_score, " +
                        "              ca2_score=EXCLUDED.ca2_score")) {
                    AuthService.setUuid(ps, 1, caId, c);
                    AuthService.setUuid(ps, 2, sid, c);
                    AuthService.setUuid(ps, 3, subId, c);
                    ps.setString(4, term);
                    ps.setDouble(5, c1);
                    ps.setDouble(6, c2);
                    AuthService.setUuid(ps, 7, AuthService.Session.userId, c);
                    ps.executeUpdate();
                    count++;
                }
            }
        }
        return count;
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
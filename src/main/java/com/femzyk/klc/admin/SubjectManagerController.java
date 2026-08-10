package com.femzyk.klc.admin;

import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.db.DatabaseManager;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Side;
import javafx.scene.control.*;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SubjectManagerController {

    @FXML private TableView<SubjectRow> table;
    @FXML private TableColumn<SubjectRow, String> colCode, colName, colClass;
    @FXML private TextField fName, fCode;
    @FXML private ComboBox<String> fClass;
    @FXML private Label status;

    private ContextMenu autoCompleteMenu;
    private List<String> allSubjectNames = new ArrayList<>();

    public static class SubjectRow {
        public String id, code, name, classLevel;

        public SubjectRow(String id, String code, String name, String cl) {
            this.id = id; this.code = code;
            this.name = name; this.classLevel = cl;
        }

        public String getCode()       { return code; }
        public String getName()       { return name; }
        public String getClassLevel() { return classLevel; }
    }

    ObservableList<SubjectRow> data = FXCollections.observableArrayList();

    @FXML
    public void initialize() {
        colCode.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getCode()));
        colName.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getName()));
        colClass.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getClassLevel()));

        table.setItems(data);
        fClass.getItems().addAll(
            "JSS1", "JSS2", "JSS3", "SS1", "SS2", "SS3");

        autoCompleteMenu = new ContextMenu();
        setupAutocomplete();

        load();
        loadSubjectNamesForAutocomplete();
    }

    private void setupAutocomplete() {
        fName.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null || newVal.isBlank()) {
                autoCompleteMenu.hide();
                return;
            }

            String filter = newVal.toLowerCase().trim();
            List<String> matches = new ArrayList<>();
            for (String s : allSubjectNames) {
                if (s.toLowerCase().contains(filter)) matches.add(s);
                if (matches.size() >= 10) break;
            }

            if (matches.isEmpty()) {
                autoCompleteMenu.hide();
                return;
            }

            autoCompleteMenu.getItems().clear();
            for (String match : matches) {
                MenuItem item = new MenuItem(match);
                item.setOnAction(e -> {
                    fName.setText(match);
                    autoCompleteMenu.hide();
                    autoSuggestCode(match);
                });
                autoCompleteMenu.getItems().add(item);
            }

            if (!autoCompleteMenu.isShowing()) {
                autoCompleteMenu.show(fName, Side.BOTTOM, 0, 0);
            }
        });

        fName.focusedProperty().addListener((obs, ov, nv) -> {
            if (!nv) autoCompleteMenu.hide();
        });
    }

    private void autoSuggestCode(String subjectName) {
        String cls = fClass.getValue();
        if (cls == null) return;

        String code = subjectName
            .replaceAll("[^A-Za-z ]", "")
            .trim()
            .toUpperCase();

        String[] words = code.split("\\s+");
        String prefix = words[0].length() >= 3
            ? words[0].substring(0, 3)
            : words[0];

        fCode.setText(prefix + "-" + cls);
    }

    private void loadSubjectNamesForAutocomplete() {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT DISTINCT subject_name FROM subjects " +
                 "ORDER BY subject_name")) {
            ResultSet rs = ps.executeQuery();
            List<String> names = new ArrayList<>();
            while (rs.next()) {
                String n = rs.getString(1);
                if (!names.contains(n)) names.add(n);
            }
            allSubjectNames = names;
        } catch (Exception e) {
            allSubjectNames = List.of(
                "ACCOUNTING", "AGRICULTURAL SCIENCE", "BASIC SCIENCE",
                "BASIC TECHNOLOGY", "BIOLOGY", "BUSINESS STUDIES",
                "CHEMISTRY", "CHRISTIAN RELIGIOUS STUDIES",
                "CIVIC EDUCATION", "COMMERCE",
                "CULTURAL AND CREATIVE ART", "DATA PROCESSING",
                "DIGITAL TECHNOLOGY", "ECONOMICS",
                "ENGLISH LANGUAGE", "FRENCH",
                "FURTHER MATHEMATICS", "GEOGRAPHY", "GOVERNMENT",
                "HAUSA LANGUAGE", "HOME ECONOMICS",
                "IGBO LANGUAGE", "ISLAMIC RELIGIOUS KNOWLEDGE",
                "LITERATURE IN ENGLISH", "MATHEMATICS",
                "OFFICE PRACTICE", "PHYSICAL HEALTH EDUCATION",
                "PHYSICS", "SECURITY EDUCATION",
                "SOCIAL STUDIES", "TECHNICAL DRAWING",
                "TRADE SUBJECT", "VISUAL ARTS",
                "YORUBA LANGUAGE"
            );
        }
    }

    void load() {
        data.clear();
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT id, subject_code, subject_name, class_level " +
                 "FROM subjects " +
                 "ORDER BY subject_name, class_level")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                data.add(new SubjectRow(
                    rs.getString(1), rs.getString(2),
                    rs.getString(3), rs.getString(4)));
            }
            if (status != null)
                status.setText("Loaded " + data.size() + " subjects");
        } catch (Exception e) {
            if (status != null)
                status.setText("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @FXML
    private void addSubject() {
        if (!AuthService.isTeacherOrAbove()) {
            status.setText("Access denied");
            return;
        }
        String name = fName.getText().trim().toUpperCase();
        String code = fCode.getText().trim().toUpperCase();
        String cls  = fClass.getValue();

        if (name.isBlank() || code.isBlank() || cls == null) {
            status.setText("Fill in Subject Name, Code and Class");
            return;
        }

        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO subjects(id, subject_name, subject_code, " +
                 "class_level, is_active, created_by) VALUES(?,?,?,?,TRUE,?)")) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, name);
            ps.setString(3, code);
            ps.setString(4, cls);
            ps.setString(5, AuthService.Session.userId);
            ps.executeUpdate();

            AuthService.logAudit("SUBJECT_ADD", "subjects", null);
            status.setText("Subject added: " + code + " - " + name);
            fName.clear();
            fCode.clear();
            load();
            loadSubjectNamesForAutocomplete();

        } catch (Exception e) {
            status.setText("Error: " + e.getMessage());
        }
    }

    @FXML
    private void deleteSubject() {
        SubjectRow r = table.getSelectionModel().getSelectedItem();
        if (r == null) {
            status.setText("Select a subject to delete");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "Delete subject: " + r.name + " (" + r.code + ")?\n" +
            "This will fail if exams or questions exist for this subject.",
            ButtonType.YES, ButtonType.NO);
        confirm.setTitle("Confirm Delete");
        confirm.showAndWait().ifPresent(btn -> {
            if (btn != ButtonType.YES) return;
            try (Connection c = DatabaseManager.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM subjects WHERE id=?")) {
                ps.setString(1, r.id);
                ps.executeUpdate();
                status.setText("Deleted: " + r.name);
                load();
            } catch (Exception e) {
                status.setText(
                    "Cannot delete - exams or questions exist for this subject.");
            }
        });
    }
}
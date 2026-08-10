package com.femzyk.klc.admin;

import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.db.DatabaseManager;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.util.converter.IntegerStringConverter;

import java.lang.reflect.Type;
import java.sql.*;
import java.util.*;

public class GradingScaleController {

    @FXML private TableView<GradeRow>           gradeTable;
    @FXML private TableColumn<GradeRow, String>  colGrade, colRemark;
    @FXML private TableColumn<GradeRow, Integer> colMin, colMax;
    @FXML private Spinner<Integer>               ca1Weight, ca2Weight, examWeight;
    @FXML private Label                          status;

    private static final Type TYPE_MAP_OBJ =
            new TypeToken<Map<String, Object>>() {}.getType();
    private static final Type TYPE_LIST_MAP_OBJ =
            new TypeToken<List<Map<String, Object>>>() {}.getType();
    private static final Type TYPE_MAP_NUMBER =
            new TypeToken<Map<String, Number>>() {}.getType();

    public static class GradeRow {
        private String grade, remark;
        private int    min, max;

        public GradeRow(String g, int min, int max, String r) {
            grade = g; this.min = min; this.max = max; remark = r;
        }

        public String  getGrade()         { return grade; }
        public void    setGrade(String v) { grade = v; }
        public Integer getMin()           { return min; }
        public void    setMin(Integer v)  { min = v; }
        public Integer getMax()           { return max; }
        public void    setMax(Integer v)  { max = v; }
        public String  getRemark()        { return remark; }
        public void    setRemark(String v){ remark = v; }
    }

    ObservableList<GradeRow> data = FXCollections.observableArrayList();

    @FXML
    public void initialize() {
        colGrade.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getGrade()));
        colMin.setCellValueFactory(c ->
            new javafx.beans.property.SimpleObjectProperty<>(
                c.getValue().getMin()));
        colMax.setCellValueFactory(c ->
            new javafx.beans.property.SimpleObjectProperty<>(
                c.getValue().getMax()));
        colRemark.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getRemark()));

        gradeTable.setEditable(true);

        colGrade.setCellFactory(TextFieldTableCell.forTableColumn());
        colGrade.setOnEditCommit(e -> e.getRowValue().setGrade(e.getNewValue()));

        colMin.setCellFactory(
            TextFieldTableCell.forTableColumn(new IntegerStringConverter()));
        colMin.setOnEditCommit(e -> e.getRowValue().setMin(e.getNewValue()));

        colMax.setCellFactory(
            TextFieldTableCell.forTableColumn(new IntegerStringConverter()));
        colMax.setOnEditCommit(e -> e.getRowValue().setMax(e.getNewValue()));

        colRemark.setCellFactory(TextFieldTableCell.forTableColumn());
        colRemark.setOnEditCommit(e -> e.getRowValue().setRemark(e.getNewValue()));

        gradeTable.setItems(data);

        ca1Weight.setValueFactory(
            new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 50, 20));
        ca2Weight.setValueFactory(
            new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 50, 20));
        examWeight.setValueFactory(
            new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 100, 60));

        load();
    }

    @FXML
    private void load() {
        data.clear();

        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT grading_scale FROM school_profile LIMIT 1")) {

            ResultSet rs = ps.executeQuery();
            if (rs.next() && rs.getString(1) != null) {
                try {
                    Gson gson = new Gson();
                    Map<String, Object> map =
                        gson.fromJson(rs.getString(1), TYPE_MAP_OBJ);

                    Object gradesRaw = map.get("grades");
                    if (gradesRaw != null) {
                        List<Map<String, Object>> grades =
                            gson.fromJson(gson.toJson(gradesRaw),
                                TYPE_LIST_MAP_OBJ);
                        for (Map<String, Object> g : grades) {
                            data.add(new GradeRow(
                                (String) g.get("grade"),
                                ((Number) g.get("min")).intValue(),
                                ((Number) g.get("max")).intValue(),
                                (String) g.get("remark")));
                        }
                    }

                    Object weightsRaw = map.get("weights");
                    if (weightsRaw != null) {
                        Map<String, Number> w =
                            gson.fromJson(gson.toJson(weightsRaw),
                                TYPE_MAP_NUMBER);
                        ca1Weight.getValueFactory().setValue(
                            w.get("ca1").intValue());
                        ca2Weight.getValueFactory().setValue(
                            w.get("ca2").intValue());
                        examWeight.getValueFactory().setValue(
                            w.get("exam").intValue());
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        // FIX: Full WAEC grading scale including E8 explicitly
        if (data.isEmpty()) {
            data.addAll(
                new GradeRow("A1", 75, 100, "Excellent"),
                new GradeRow("B2", 70,  74, "Very Good"),
                new GradeRow("B3", 65,  69, "Very Good"),
                new GradeRow("C4", 60,  64, "Good"),
                new GradeRow("C5", 55,  59, "Good"),
                new GradeRow("C6", 50,  54, "Credit"),
                new GradeRow("D7", 45,  49, "Pass"),
                new GradeRow("E8", 40,  44, "Pass"),   // E grade
                new GradeRow("F9",  0,  39, "Fail")
            );
        }

        status.setText("WAEC A1-F9 scale loaded (including E8). " +
                       "Double-click cells to edit. CA1 + CA2 + Exam = 100%");
    }

    @FXML
    private void save() {
        int sum = ca1Weight.getValue() + ca2Weight.getValue() +
                  examWeight.getValue();
        if (sum != 100) {
            status.setText("ERROR: Weights must sum to 100. Currently: " + sum);
            status.setStyle("-fx-text-fill:#ef4444; -fx-font-weight:bold;");
            return;
        }

        try (Connection c = DatabaseManager.getConnection()) {

            List<Map<String, Object>> grades = new ArrayList<>();
            for (GradeRow gr : data) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("grade",  gr.getGrade());
                m.put("min",    gr.getMin());
                m.put("max",    gr.getMax());
                m.put("remark", gr.getRemark());
                grades.add(m);
            }

            Map<String, Integer> weights = new LinkedHashMap<>();
            weights.put("ca1",  ca1Weight.getValue());
            weights.put("ca2",  ca2Weight.getValue());
            weights.put("exam", examWeight.getValue());

            Map<String, Object> root = new LinkedHashMap<>();
            root.put("grades",  grades);
            root.put("weights", weights);

            String json = new Gson().toJson(root);

            boolean isH2 = AuthService.isH2(c);

            // FIX: PostgreSQL uses ?::jsonb, H2 uses plain string
            String sql = isH2
                ? "UPDATE school_profile SET grading_scale = ?"
                : "UPDATE school_profile SET grading_scale = ?::jsonb";

            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, json);
                int rows = ps.executeUpdate();
                if (rows == 0) {
                    // No school_profile row exists - insert one
                    String insertSql = isH2
                        ? "INSERT INTO school_profile(id, school_name, grading_scale) VALUES(RANDOM_UUID(), 'KNOWLEDGE LAND COLLEGE', ?)"
                        : "INSERT INTO school_profile(id, school_name, grading_scale) VALUES(uuid_generate_v4(), 'KNOWLEDGE LAND COLLEGE', ?::jsonb)";
                    try (PreparedStatement ins = c.prepareStatement(insertSql)) {
                        ins.setString(1, json);
                        ins.executeUpdate();
                    }
                }
            }

            AuthService.logAudit("GRADING_SCALE_SAVE", "school_profile", null);
            status.setText(
                "Grading scale saved - CA1=" + ca1Weight.getValue() +
                "% CA2=" + ca2Weight.getValue() +
                "% Exam=" + examWeight.getValue() +
                "% - Applied to all future CA aggregations.");
            status.setStyle("-fx-text-fill:#0f7a3a; -fx-font-weight:bold;");

        } catch (Exception e) {
            status.setText("Save error: " + e.getMessage());
            status.setStyle("-fx-text-fill:#ef4444; -fx-font-weight:bold;");
            e.printStackTrace();
        }
    }

    // =========================================================================
    //  STATIC UTILITIES
    // =========================================================================
    public static String gradeFor(double total, Connection c)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT grading_scale FROM school_profile LIMIT 1")) {
            ResultSet rs = ps.executeQuery();
            if (rs.next() && rs.getString(1) != null) {
                Gson gson = new Gson();
                Map<String, Object> map =
                    gson.fromJson(rs.getString(1), TYPE_MAP_OBJ);
                Object raw = map.get("grades");
                if (raw != null) {
                    List<Map<String, Object>> grades =
                        gson.fromJson(gson.toJson(raw), TYPE_LIST_MAP_OBJ);
                    for (Map<String, Object> g : grades) {
                        int min = ((Number) g.get("min")).intValue();
                        int max = ((Number) g.get("max")).intValue();
                        if (total >= min && total <= max)
                            return (String) g.get("grade");
                    }
                }
            }
        } catch (Exception ignored) {}

        // WAEC fallback including E8
        if (total >= 75) return "A1";
        if (total >= 70) return "B2";
        if (total >= 65) return "B3";
        if (total >= 60) return "C4";
        if (total >= 55) return "C5";
        if (total >= 50) return "C6";
        if (total >= 45) return "D7";
        if (total >= 40) return "E8";
        return "F9";
    }

    public static int[] getWeights(Connection c) throws SQLException {
        int[] w = {20, 20, 60};
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT grading_scale FROM school_profile LIMIT 1")) {
            ResultSet rs = ps.executeQuery();
            if (rs.next() && rs.getString(1) != null) {
                Gson gson = new Gson();
                Map<String, Object> map =
                    gson.fromJson(rs.getString(1), TYPE_MAP_OBJ);
                Object raw = map.get("weights");
                if (raw != null) {
                    Map<String, Number> ww =
                        gson.fromJson(gson.toJson(raw), TYPE_MAP_NUMBER);
                    w[0] = ww.get("ca1").intValue();
                    w[1] = ww.get("ca2").intValue();
                    w[2] = ww.get("exam").intValue();
                }
            }
        } catch (Exception ignored) {}
        return w;
    }
}
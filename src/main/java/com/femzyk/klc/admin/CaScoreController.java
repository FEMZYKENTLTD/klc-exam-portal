package com.femzyk.klc.admin;

import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.db.DatabaseManager;
import com.opencsv.CSVReader;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.UUID;

public class CaScoreController {
    @FXML private ComboBox<String> subjectBox, classBox, termBox;
    @FXML private Label status;
    @FXML private TableView<CaRow> table;
    @FXML private TableColumn<CaRow,String> colAdm, colName, colCa1, colCa2, colExam, colTotal, colGrade;

    public static class CaRow {
        String studentId, admission, name, ca1, ca2, exam, total, grade;
        CaRow(String sid, String a, String n, String c1, String c2, String ex, String t, String g){
            studentId=sid; admission=a; name=n; ca1=c1; ca2=c2; exam=ex; total=t; grade=g;
        }
        public String getAdmission(){return admission;}
        public String getName(){return name;}
        public String getCa1(){return ca1;}
        public String getCa2(){return ca2;}
        public String getExam(){return exam;}
        public String getTotal(){return total;}
        public String getGrade(){return grade;}
    }
    ObservableList<CaRow> data = FXCollections.observableArrayList();
    private String subjectId;
    private String session = currentSession();

    /** Current academic session from school_profile, safe fallback. */
    static String currentSession() {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT session_current FROM school_profile LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                String s = rs.getString(1);
                if (s != null && !s.isBlank()) return s;
            }
        } catch (Exception ignored) {}
        return "2024/2025";
    }

    @FXML public void initialize(){
        colAdm.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getAdmission()));
        colName.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getName()));
        colCa1.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getCa1()));
        colCa2.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getCa2()));
        colExam.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getExam()));
        colTotal.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getTotal()));
        colGrade.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getGrade()));
        table.setItems(data);
        classBox.getItems().addAll("JSS1","JSS2","JSS3","SS1","SS2","SS3");
        termBox.getItems().addAll("1st","2nd","3rd");
        termBox.setValue("1st");
        loadSubjects();
    }
    void loadSubjects(){
        try(Connection c=DatabaseManager.getConnection();
            PreparedStatement ps=c.prepareStatement("SELECT subject_code FROM subjects WHERE is_active=true ORDER BY subject_code")){
            ResultSet rs=ps.executeQuery();
            subjectBox.getItems().clear();
            while(rs.next()) subjectBox.getItems().add(rs.getString(1));
        }catch(Exception e){}
    }
    @FXML private void loadScores(){
        if(subjectBox.getValue()==null || classBox.getValue()==null){ status.setText("Pick Subject + Class"); return; }
        data.clear();
        try(Connection c=DatabaseManager.getConnection()){
            try(PreparedStatement ps=c.prepareStatement("SELECT id FROM subjects WHERE subject_code=?")){
                ps.setString(1, subjectBox.getValue()); ResultSet rs=ps.executeQuery(); if(!rs.next()) return; subjectId=rs.getString(1);
            }
            String sql = """
                SELECT u.id, sp.admission_no, u.full_name,
                       COALESCE(cs.ca1_score,0), COALESCE(cs.ca2_score,0), COALESCE(cs.exam_score,0),
                       COALESCE(cs.total_score,0), cs.grade
                FROM student_profiles sp
                JOIN users u ON u.id=sp.user_id
                LEFT JOIN ca_scores cs ON cs.student_id=u.id AND cs.subject_id=? AND cs.term=? AND cs.session=?
                WHERE sp.class_level=? AND sp.status='ACTIVE'
                ORDER BY sp.admission_no
            """;
            try(PreparedStatement ps=c.prepareStatement(sql)){
                AuthService.setUuid(ps, 1, subjectId, c);
                ps.setString(2, termBox.getValue());
                ps.setString(3, session);
                ps.setString(4, classBox.getValue());
                ResultSet rs=ps.executeQuery();
                while(rs.next()){
                    data.add(new CaRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        fmt(rs.getDouble(4)), fmt(rs.getDouble(5)), fmt(rs.getDouble(6)),
                        fmt(rs.getDouble(7)), rs.getString(8)==null?"":rs.getString(8)));
                }
            }
            int[] w = GradingScaleController.getWeights(c);
            status.setText("Loaded "+data.size()+" students - CA1 "+w[0]+"% + CA2 "+w[1]+"% + Exam "+w[2]+"% = 100% - Configurable in Grading Scale");
        }catch(Exception e){ status.setText(e.getMessage()); e.printStackTrace();}
    }
    private String fmt(double d){ return d==0?"": String.format("%.1f",d); }

    @FXML private void importCaCsv(){
        if(subjectId==null){ status.setText("Load scores first (pick Subject/Class)"); return; }
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV","*.csv"));
        var file = fc.showOpenDialog(table.getScene().getWindow());
        if(file==null) return;
        int up=0;
        try(Connection c=DatabaseManager.getConnection(); CSVReader r=new CSVReader(new FileReader(file, StandardCharsets.UTF_8))){
            String[] header = r.readNext();
            String[] row;
            while((row = r.readNext()) != null){
                String admission = get(row, header, "admission_no",0);
                double ca1 = parse(get(row, header, "ca1",1));
                double ca2 = parse(get(row, header, "ca2",2));
                String studentId = null;
                try(PreparedStatement ps=c.prepareStatement("SELECT user_id FROM student_profiles WHERE admission_no=?")){
                    ps.setString(1, admission); ResultSet rs=ps.executeQuery(); if(rs.next()) studentId = rs.getString(1); else continue;
                }
                try(PreparedStatement del=c.prepareStatement("DELETE FROM ca_scores WHERE student_id=? AND subject_id=? AND term=? AND session=?")){
                    AuthService.setUuid(del, 1, studentId, c); AuthService.setUuid(del, 2, subjectId, c);
                    del.setString(3, termBox.getValue()); del.setString(4, session); del.executeUpdate();
                }
                try(PreparedStatement ins=c.prepareStatement("INSERT INTO ca_scores(id, student_id, subject_id, class_level, term, session, ca1_score, ca2_score) VALUES(?,?,?,?,?,?,?,?)")){
                    AuthService.setUuid(ins, 1, UUID.randomUUID().toString(), c); AuthService.setUuid(ins, 2, studentId, c);
                    AuthService.setUuid(ins, 3, subjectId, c); ins.setString(4, classBox.getValue());
                    ins.setString(5, termBox.getValue()); ins.setString(6, session);
                    ins.setDouble(7, ca1); ins.setDouble(8, ca2); ins.executeUpdate();
                    up++;
                }
            }
            status.setText("Imported CA scores for "+up+" students. Now run Aggregate Exam Scores.");
            loadScores();
        }catch(Exception e){ status.setText("Import error: "+e.getMessage()); e.printStackTrace();}
    }

    @FXML private void aggregateExam(){
        if(subjectId==null) return;
        int updated=0;
        try(Connection c=DatabaseManager.getConnection()){
            int[] weights = GradingScaleController.getWeights(c);
            double examWeight = weights[2];
            // Pull latest exam results
            String sql = """
                SELECT r.student_id, r.percentage
                FROM results r
                JOIN exams e ON e.id=r.exam_id
                WHERE e.subject_id=? AND e.class_level=? AND e.term=?
            """;
            try(PreparedStatement ps=c.prepareStatement(sql)){
                AuthService.setUuid(ps, 1, subjectId, c);
                ps.setString(2, classBox.getValue());
                ps.setString(3, termBox.getValue());
                ResultSet rs=ps.executeQuery();
                while(rs.next()){
                    String sid = rs.getString(1);
                    double examScore = rs.getDouble(2) * examWeight / 100.0;
                    double ca1=0, ca2=0;
                    try(PreparedStatement ps2=c.prepareStatement("SELECT ca1_score, ca2_score FROM ca_scores WHERE student_id=? AND subject_id=? AND term=? AND session=?")){
                        AuthService.setUuid(ps2, 1, sid, c); AuthService.setUuid(ps2, 2, subjectId, c); ps2.setString(3, termBox.getValue()); ps2.setString(4, session);
                        ResultSet rs2=ps2.executeQuery(); if(rs2.next()){ ca1=rs2.getDouble(1); ca2=rs2.getDouble(2); }
                    }
                    double total = ca1 + ca2 + examScore;
                    String grade = GradingScaleController.gradeFor(total, c);
                    try(PreparedStatement up=c.prepareStatement("UPDATE ca_scores SET exam_score=?, total_score=?, grade=? WHERE student_id=? AND subject_id=? AND term=? AND session=?")){
                        up.setDouble(1, examScore); up.setDouble(2, total); up.setString(3, grade);
                        AuthService.setUuid(up, 4, sid, c); AuthService.setUuid(up, 5, subjectId, c);
                        up.setString(6, termBox.getValue()); up.setString(7, session);
                        int n = up.executeUpdate();
                        if(n==0){
                            try(PreparedStatement ins=c.prepareStatement("INSERT INTO ca_scores(id, student_id, subject_id, class_level, term, session, ca1_score, ca2_score, exam_score, total_score, grade) VALUES(?,?,?,?,?,?,?,?,?,?,?)")){
                                AuthService.setUuid(ins, 1, UUID.randomUUID().toString(), c); AuthService.setUuid(ins, 2, sid, c); AuthService.setUuid(ins, 3, subjectId, c);
                                ins.setString(4, classBox.getValue()); ins.setString(5, termBox.getValue()); ins.setString(6, session);
                                ins.setDouble(7, ca1); ins.setDouble(8, ca2); ins.setDouble(9, examScore); ins.setDouble(10, total); ins.setString(11, grade);
                                ins.executeUpdate();
                            }
                        }
                    }
                    updated++;
                }
            }
            // compute positions
            try(PreparedStatement ps=c.prepareStatement("""
                SELECT student_id, total_score FROM ca_scores
                WHERE subject_id=? AND class_level=? AND term=? AND session=?
                ORDER BY total_score DESC
            """)){
                AuthService.setUuid(ps, 1, subjectId, c); ps.setString(2, classBox.getValue()); ps.setString(3, termBox.getValue()); ps.setString(4, session);
                ResultSet rs=ps.executeQuery();
                java.util.List<String> sids=new java.util.ArrayList<>(); java.util.List<Double> scores=new java.util.ArrayList<>();
                while(rs.next()){ sids.add(rs.getString(1)); scores.add(rs.getDouble(2)); }
                java.util.List<Integer> positions=com.femzyk.klc.util.Ranking.positions(scores);
                for(int i=0;i<sids.size();i++){
                    try(PreparedStatement up=c.prepareStatement("UPDATE ca_scores SET position=? WHERE student_id=? AND subject_id=? AND term=? AND session=?")){
                        up.setInt(1, positions.get(i)); AuthService.setUuid(up, 2, sids.get(i), c);
                        AuthService.setUuid(up, 3, subjectId, c); up.setString(4, termBox.getValue()); up.setString(5, session); up.executeUpdate();
                    }
                }
            }
            status.setText("Aggregated "+updated+" exam scores → Total + Grade + Position - using configurable grading scale");
            loadScores();
        }catch(Exception e){ status.setText(e.getMessage()); e.printStackTrace();}
    }

    private double parse(String s){ try{ return Double.parseDouble(s);}catch(Exception e){return 0;}}
    private String get(String[] row, String[] header, String key, int fallback){
        if(header!=null){ for(int i=0;i<header.length && i<row.length;i++) if(header[i].trim().equalsIgnoreCase(key)) return row[i].trim(); }
        return fallback < row.length ? row[fallback].trim() : "";
    }
}

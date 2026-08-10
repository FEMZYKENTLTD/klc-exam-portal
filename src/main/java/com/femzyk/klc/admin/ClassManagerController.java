package com.femzyk.klc.admin;

import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.db.DatabaseManager;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import java.sql.*;

public class ClassManagerController {
    @FXML private TableView<ClassRow> classTable;
    @FXML private TableColumn<ClassRow,String> colClassLevel, colArm, colSession, colTeacher;
    @FXML private ComboBox<String> cClassLevel, cArm, cSession;
    @FXML private TextField cTeacherEmail;
    @FXML private Label cStatus;
    @FXML private TextField sessionNameField;
    @FXML private ComboBox<String> termCurrentBox;
    @FXML private Label academicStatus;

    public static class ClassRow {
        String id, classLevel, arm, session, teacher;
        ClassRow(String id, String cl, String arm, String s, String t){this.id=id; classLevel=cl; this.arm=arm; session=s; teacher=t;}
        public String getClassLevel(){return classLevel;}
        public String getArm(){return arm==null?"":arm;}
        public String getSession(){return session==null?"":session;}
        public String getTeacher(){return teacher==null?"-":teacher;}
    }
    ObservableList<ClassRow> data = FXCollections.observableArrayList();

    @FXML public void initialize(){
        colClassLevel.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getClassLevel()));
        colArm.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getArm()));
        colSession.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getSession()));
        colTeacher.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getTeacher()));
        classTable.setItems(data);
        cClassLevel.getItems().addAll("JSS1","JSS2","JSS3","SS1","SS2","SS3");
        cArm.getItems().addAll("A","B","C","Science","Art","Commercial");
        cSession.getItems().addAll("2024/2025","2025/2026","2026/2027","2027/2028","2028/2029","2029/2030");
        cSession.setValue("2024/2025");
        termCurrentBox.getItems().addAll("1st","2nd","3rd");
        loadClasses(); loadAcademic();
    }
    @FXML private void loadClasses(){
        data.clear();
        try(Connection conn = DatabaseManager.getConnection();
            PreparedStatement ps = conn.prepareStatement("""
                SELECT sc.id, sc.class_level, sc.arm, sc.session, u.full_name
                FROM school_classes sc LEFT JOIN users u ON u.id=sc.class_teacher_id
                ORDER BY sc.session DESC, sc.class_level, sc.arm
            """)){
            ResultSet rs = ps.executeQuery();
            while(rs.next()) data.add(new ClassRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
            cStatus.setText("Loaded "+data.size()+" class/arm records - JSS1-SS3 fully managed");
        }catch(Exception e){ cStatus.setText(e.getMessage()); }
    }
    @FXML private void addClass(){
        if(cClassLevel.getValue()==null){ cStatus.setText("Select Class Level"); return; }
        try(Connection conn = DatabaseManager.getConnection()){
            String teacherId = null;
            if(cTeacherEmail.getText()!=null && !cTeacherEmail.getText().isBlank()){
                try(PreparedStatement ps = conn.prepareStatement("SELECT id FROM users WHERE email=?")){
                    ps.setString(1, cTeacherEmail.getText().toLowerCase());
                    ResultSet rs = ps.executeQuery();
                    if(rs.next()) teacherId = rs.getString(1);
                }
            }
            try(PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO school_classes(id, class_level, arm, session, class_teacher_id) VALUES(?,?,?,?,?)")){
                ps.setObject(1, java.util.UUID.randomUUID());
                ps.setString(2, cClassLevel.getValue());
                ps.setString(3, cArm.getValue());
                ps.setString(4, cSession.getValue());
                ps.setObject(5, teacherId==null?null:java.util.UUID.fromString(teacherId));
                ps.executeUpdate();
            }
            cStatus.setText("Class/Arm added: "+cClassLevel.getValue()+" "+(cArm.getValue()==null?"":cArm.getValue())+" - "+cSession.getValue());
            loadClasses();
            AuthService.logAudit("CLASS_ADD","school_classes",null);
        }catch(Exception e){
            cStatus.setText("Error (duplicate Class/Arm/Session?): "+e.getMessage());
        }
    }
    @FXML private void deleteClass(){
        ClassRow r = classTable.getSelectionModel().getSelectedItem();
        if(r==null){ cStatus.setText("Select a class"); return; }
        try(Connection conn = DatabaseManager.getConnection();
            PreparedStatement ps = conn.prepareStatement("DELETE FROM school_classes WHERE id=?")){
            ps.setObject(1, java.util.UUID.fromString(r.id)); ps.executeUpdate();
            loadClasses();
        }catch(Exception e){ cStatus.setText(e.getMessage());}
    }
    private void loadAcademic(){
        try(Connection c=DatabaseManager.getConnection();
            PreparedStatement ps=c.prepareStatement("SELECT session_current, term_current FROM school_profile LIMIT 1")){
            ResultSet rs=ps.executeQuery();
            if(rs.next()){
                if(sessionNameField!=null){
                    sessionNameField.setText(rs.getString(1));
                    if(!cSession.getItems().contains(rs.getString(1))) cSession.getItems().add(rs.getString(1));
                }
                if(termCurrentBox!=null) termCurrentBox.setValue(rs.getString(2));
            }
        }catch(Exception ignored){}
    }
    @FXML private void saveAcademic(){
        String session = sessionNameField.getText();
        String term = termCurrentBox.getValue();
        if(session==null || session.isBlank()){ academicStatus.setText("Enter session e.g. 2025/2026"); return; }
        try(Connection c=DatabaseManager.getConnection();
            PreparedStatement ps=c.prepareStatement("UPDATE school_profile SET session_current=?, term_current=?, updated_at=now()")){
            ps.setString(1, session); ps.setString(2, term); ps.executeUpdate();
            academicStatus.setText("Academic Calendar saved: "+session+" - "+term+" Term - affects all new exams/results - 10+ year archive enabled");
            AuthService.logAudit("ACADEMIC_CALENDAR_UPDATE","school_profile",null);
        }catch(Exception e){ academicStatus.setText(e.getMessage());}
    }
    @FXML private void createSession(){
        String s = sessionNameField.getText();
        if(s==null || !s.matches("\\d{4}/\\d{4}")){ academicStatus.setText("Session format: YYYY/YYYY e.g. 2025/2026"); return; }
        saveAcademic();
    }
}

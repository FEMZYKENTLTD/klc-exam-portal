package com.femzyk.klc.admin;

import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.db.DatabaseManager;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import java.io.File;
import java.sql.*;

public class StudyMaterialsController {
    @FXML private ComboBox<String> subjectBox, classBox;
    @FXML private TextField titleField, filePathField;
    @FXML private TextArea descArea;
    @FXML private TableView<MRow> table;
    @FXML private TableColumn<MRow,String> colTitle, colSubject, colClass, colDate;
    @FXML private Label status;
    private java.util.Map<String,String> subjectMap = new java.util.HashMap<>();

    public static class MRow {
        String title, subject, classLevel, date;
        MRow(String t,String s,String c,String d){title=t;subject=s;classLevel=c;date=d;}
        public String getTitle(){return title;}
        public String getSubject(){return subject;}
        public String getClassLevel(){return classLevel;}
        public String getDate(){return date;}
    }
    ObservableList<MRow> data = FXCollections.observableArrayList();

    @FXML public void initialize(){
        colTitle.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getTitle()));
        colSubject.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getSubject()));
        colClass.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getClassLevel()));
        colDate.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getDate()));
        table.setItems(data);
        classBox.getItems().addAll("JSS1","JSS2","JSS3","SS1","SS2","SS3");
        loadSubjects(); load();
    }
    void loadSubjects(){
        try(Connection c=DatabaseManager.getConnection();
            PreparedStatement ps=c.prepareStatement("SELECT id, subject_code FROM subjects WHERE is_active=true ORDER BY subject_code")){
            ResultSet rs=ps.executeQuery(); subjectBox.getItems().clear(); subjectMap.clear();
            while(rs.next()){ subjectBox.getItems().add(rs.getString(2)); subjectMap.put(rs.getString(2), rs.getString(1)); }
        }catch(Exception ignored){}
    }
    @FXML private void chooseFile(){
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF / Docs","*.pdf","*.docx","*.pptx","*.mp4"));
        File f = fc.showOpenDialog(table.getScene().getWindow());
        if(f!=null){ filePathField.setText(f.getAbsolutePath()); status.setText("File selected - upload to Supabase Storage for cloud delivery (path stored locally for now)"); }
    }
    @FXML private void upload(){
        if(subjectBox.getValue()==null){ status.setText("Pick subject"); return; }
        try(Connection c=DatabaseManager.getConnection();
            PreparedStatement ps=c.prepareStatement("INSERT INTO study_materials(id, subject_id, class_level, title, description, file_url, uploaded_by) VALUES(?,?,?,?,?,?,?)")){
            ps.setObject(1, java.util.UUID.randomUUID());
            ps.setObject(2, java.util.UUID.fromString(subjectMap.get(subjectBox.getValue())));
            ps.setString(3, classBox.getValue());
            ps.setString(4, titleField.getText());
            ps.setString(5, descArea.getText());
            ps.setString(6, filePathField.getText());
            ps.setObject(7, java.util.UUID.fromString(AuthService.Session.userId));
            ps.executeUpdate();
            status.setText("Study material uploaded - students see in Practice Mode / E-Library");
            titleField.clear(); descArea.clear(); filePathField.clear();
            load();
        }catch(Exception e){ status.setText(e.getMessage()); }
    }
    @FXML private void load(){
        data.clear();
        try(Connection c=DatabaseManager.getConnection();
            PreparedStatement ps=c.prepareStatement("""
              SELECT sm.title, s.subject_code, sm.class_level, sm.created_at
              FROM study_materials sm JOIN subjects s ON s.id=sm.subject_id
              ORDER BY sm.created_at DESC LIMIT 200""")){
            ResultSet rs=ps.executeQuery();
            while(rs.next()) data.add(new MRow(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getTimestamp(4).toLocalDateTime().toLocalDate().toString()));
        }catch(Exception ignored){}
    }
}

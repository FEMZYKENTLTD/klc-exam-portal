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
        if(f!=null){ filePathField.setText(f.getAbsolutePath()); status.setText("File selected - it will be uploaded to Supabase Storage on save (cloud URL stored, students can download it anywhere)."); }
    }
    @FXML private void upload(){
        if(subjectBox.getValue()==null){ status.setText("Pick subject"); return; }
        try(Connection c=DatabaseManager.getConnection();
            PreparedStatement ps=c.prepareStatement("INSERT INTO study_materials(id, subject_id, class_level, title, description, file_url, uploaded_by) VALUES(?,?,?,?,?,?,?)")){
            AuthService.setUuid(ps, 1, java.util.UUID.randomUUID().toString(), c);
            AuthService.setUuid(ps, 2, subjectMap.get(subjectBox.getValue()), c);
            ps.setString(3, classBox.getValue());
            ps.setString(4, titleField.getText());
            ps.setString(5, descArea.getText());
            // Cloud delivery: upload the real file to Supabase Storage and
            // store its URL (a local path would be useless to students on
            // other PCs). Falls back to the local path only when Storage is
            // not configured / PC is offline - flagged in the status line.
            String storedRef = filePathField.getText();
            boolean localOnly = false;
            java.io.File local = storedRef == null ? null : new java.io.File(storedRef);
            if (local != null && local.isFile()) {
                String url = com.femzyk.klc.util.StorageService.upload(local, "study-materials");
                if (url != null && !url.isBlank()) storedRef = url; else localOnly = true;
            }
            ps.setString(6, storedRef);
            AuthService.setUuid(ps, 7, AuthService.Session.userId, c);
            ps.executeUpdate();
            status.setText(localOnly ? "Saved with LOCAL file path (Storage offline/unconfigured) - students on this PC only. Re-upload when online for cloud delivery." : "Study material uploaded - students see in Practice Mode / E-Library");
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

package com.femzyk.klc.admin;

import com.femzyk.klc.db.DatabaseManager;
import com.femzyk.klc.util.IdCardPdf;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import java.sql.*;

public class IdCardController {
    @FXML private TableView<SRow> table;
    @FXML private TableColumn<SRow,String> colAdm, colName, colClass;
    @FXML private Label status;
    @FXML private ComboBox<String> classFilter;

    public static class SRow {
        String userId, admission, name, classLevel;
        SRow(String uid,String a,String n,String c){userId=uid;admission=a;name=n;classLevel=c;}
        public String getAdmission(){return admission;}
        public String getName(){return name;}
        public String getClassLevel(){return classLevel;}
    }
    ObservableList<SRow> data = FXCollections.observableArrayList();

    @FXML public void initialize(){
        colAdm.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getAdmission()));
        colName.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getName()));
        colClass.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getClassLevel()));
        table.setItems(data);
        classFilter.getItems().addAll("ALL","JSS1","JSS2","JSS3","SS1","SS2","SS3");
        classFilter.setValue("ALL");
        load();
    }
    @FXML private void load(){
        data.clear();
        try(Connection c=DatabaseManager.getConnection()){
            String sql = "SELECT u.id, sp.admission_no, u.full_name, sp.class_level FROM student_profiles sp JOIN users u ON u.id=sp.user_id WHERE sp.status='ACTIVE'";
            if(!"ALL".equals(classFilter.getValue())) sql += " AND sp.class_level='"+classFilter.getValue()+"'";
            sql += " ORDER BY sp.admission_no LIMIT 500";
            ResultSet rs = c.createStatement().executeQuery(sql);
            while(rs.next()) data.add(new SRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
            status.setText("Loaded "+data.size()+" students");
        }catch(Exception e){ status.setText(e.getMessage()); }
    }
    @FXML private void generateSelected(){
        SRow r = table.getSelectionModel().getSelectedItem();
        if(r==null){ status.setText("Select a student"); return; }
        try{
            String out = IdCardPdf.generate(r.admission, r.name, r.classLevel, "2024/2025");
            status.setText("ID Card generated: "+out+" - with QR verification");
            new Alert(Alert.AlertType.INFORMATION, "ID Card saved: "+out).showAndWait();
        }catch(Exception e){ status.setText(e.getMessage()); e.printStackTrace();}
    }
    @FXML private void generateBulk(){
        int n=0;
        for(SRow r: data){
            try{ IdCardPdf.generate(r.admission, r.name, r.classLevel, "2024/2025"); n++; }catch(Exception ignored){}
        }
        status.setText("Bulk generated "+n+" ID cards - PDF files in app folder");
        new Alert(Alert.AlertType.INFORMATION, "Bulk ID Cards: "+n+" generated").showAndWait();
    }
}

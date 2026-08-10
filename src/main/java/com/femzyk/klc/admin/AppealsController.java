package com.femzyk.klc.admin;

import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.db.DatabaseManager;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import java.sql.*;

public class AppealsController {
    @FXML private TableView<ApRow> table;
    @FXML private TableColumn<ApRow,String> colStudent, colSubject, colReason, colStatus, colDate;
    @FXML private TextArea responseArea;
    @FXML private ComboBox<String> statusBox;
    @FXML private Label statusLabel;

    public static class ApRow {
        String id, student, subject, reason, status, date;
        ApRow(String id, String s, String sub, String r, String st, String d){this.id=id;student=s;subject=sub;reason=r;status=st;date=d;}
        public String getStudent(){return student;}
        public String getSubject(){return subject;}
        public String getReason(){return reason;}
        public String getStatus(){return status;}
        public String getDate(){return date;}
    }
    ObservableList<ApRow> data = FXCollections.observableArrayList();

    @FXML public void initialize(){
        colStudent.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getStudent()));
        colSubject.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getSubject()));
        colReason.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getReason()));
        colStatus.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getStatus()));
        colDate.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getDate()));
        table.setItems(data);
        statusBox.getItems().addAll("OPEN","IN_REVIEW","RESOLVED","REJECTED");
        statusBox.setValue("RESOLVED");
        load();
    }
    @FXML private void load(){
        data.clear();
        try(Connection c=DatabaseManager.getConnection();
            PreparedStatement ps=c.prepareStatement("""
              SELECT ra.id, u.full_name, ra.subject_code, ra.reason, ra.status, ra.created_at
              FROM result_appeals ra JOIN users u ON u.id=ra.student_id
              ORDER BY ra.created_at DESC LIMIT 200""")){
            ResultSet rs=ps.executeQuery();
            while(rs.next()) data.add(new ApRow(
                rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5),
                rs.getTimestamp(6).toLocalDateTime().toLocalDate().toString()));
            statusLabel.setText("Loaded "+data.size()+" appeals");
        }catch(Exception e){ statusLabel.setText(e.getMessage()); }
    }
    @FXML private void resolve(){
        ApRow r = table.getSelectionModel().getSelectedItem();
        if(r==null){ statusLabel.setText("Select an appeal"); return; }
        try(Connection c=DatabaseManager.getConnection();
            PreparedStatement ps=c.prepareStatement("UPDATE result_appeals SET status=?, admin_response=?, resolved_by=?, resolved_at=now() WHERE id=?")){
            ps.setString(1, statusBox.getValue());
            ps.setString(2, responseArea.getText());
            ps.setObject(3, java.util.UUID.fromString(AuthService.Session.userId));
            ps.setObject(4, java.util.UUID.fromString(r.id));
            ps.executeUpdate();
            statusLabel.setText("Appeal "+statusBox.getValue()+" - audit logged");
            AuthService.logAudit("APPEAL_RESOLVE","result_appeals", r.id);
            load(); responseArea.clear();
        }catch(Exception e){ statusLabel.setText(e.getMessage()); }
    }
}

package com.femzyk.klc.student;

import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.db.DatabaseManager;
import com.femzyk.klc.util.GraduationCertificatePdf;
import com.femzyk.klc.util.ReportCardPdf;
import com.femzyk.klc.util.TranscriptPdf;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import java.sql.*;

public class ResultViewerController {
    @FXML private TableView<R> table;
    @FXML private TableColumn<R,String> colSub, colScore, colGrade, colDate;
    @FXML private Label summaryLabel;
    @FXML private TextArea appealText;
    @FXML private TextField appealSubjectField;
    @FXML private ComboBox<String> termBox;

    public static class R {
        String resultId, subject, score, grade, date;
        R(String rid, String s, String sc, String g, String d){resultId=rid; subject=s;score=sc;grade=g;date=d;}
        public String getSubject(){return subject;}
        public String getScore(){return score;}
        public String getGrade(){return grade;}
        public String getDate(){return date;}
    }
    ObservableList<R> data = FXCollections.observableArrayList();

    @FXML public void initialize(){
        colSub.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getSubject()));
        colScore.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getScore()));
        colGrade.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getGrade()));
        colDate.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getDate()));
        table.setItems(data);
        table.getSelectionModel().selectedItemProperty().addListener((o,a,b)->{
            if(b!=null && appealSubjectField!=null) appealSubjectField.setText(b.subject);
        });
        if(termBox!=null){
            termBox.getItems().addAll("1st","2nd","3rd");
            termBox.setValue("1st");
            termBox.valueProperty().addListener((o,a,b)-> load());
        }
        load();
    }
    void load(){
        data.clear();
        try(Connection c=DatabaseManager.getConnection();
            PreparedStatement ps=c.prepareStatement("""
              SELECT r.id, s.subject_code, r.percentage, r.grade, r.created_at
              FROM results r JOIN exams e ON e.id=r.exam_id JOIN subjects s ON s.id=e.subject_id
              WHERE r.student_id=? ORDER BY r.created_at DESC
            """)){
            ps.setObject(1, java.util.UUID.fromString(AuthService.Session.userId));
            ResultSet rs=ps.executeQuery();
            int cnt=0; double sum=0;
            while(rs.next()){
                double pct = rs.getDouble(3);
                sum+=pct; cnt++;
                data.add(new R(rs.getString(1), rs.getString(2), String.format("%.1f%%", pct), rs.getString(4)==null?"-":rs.getString(4),
                    rs.getTimestamp(5).toLocalDateTime().toLocalDate().toString()));
            }
            summaryLabel.setText(cnt==0? "No results yet - take an exam!"
                : String.format("Lifetime Results: %d exams | Cumulative Average: %.1f%% | Result PIN: SURNAME+CLASS", cnt, sum/cnt));
        }catch(Exception e){ summaryLabel.setText(e.getMessage());}
    }
    @FXML private void downloadReport(){
        try{
            String term = termBox != null && termBox.getValue()!=null ? termBox.getValue() : "1st";
            String out = ReportCardPdf.generateForStudent(AuthService.Session.userId, null, term, "2024/2025");
            new Alert(Alert.AlertType.INFORMATION, "Official KLC Term Report Card saved:\n"+out+"\n\nNO FEMZYK watermark - KLC Official only\nIncludes QR verification code").show();
        }catch(Exception e){ e.printStackTrace(); new Alert(Alert.AlertType.ERROR, e.getMessage()).show();}
    }
    @FXML private void downloadTranscript(){
        try{
            String out = TranscriptPdf.generateCumulativeTranscript(AuthService.Session.userId, null);
            new Alert(Alert.AlertType.INFORMATION, "Cumulative Transcript JSS1-SS3 saved:\n"+out+"\n\nIncludes all Terms/Sessions, CGPA, Principal signature").show();
        }catch(Exception e){ e.printStackTrace(); new Alert(Alert.AlertType.ERROR, e.getMessage()).show(); }
    }
    @FXML private void downloadGraduation(){
        try{
            String out = GraduationCertificatePdf.generate(AuthService.Session.userId, null);
            new Alert(Alert.AlertType.INFORMATION, "Graduation Certificate saved:\n"+out+"\n\nKNOWLEDGE LAND COLLEGE - Official").show();
        }catch(Exception e){ new Alert(Alert.AlertType.ERROR, e.getMessage()).show(); }
    }
    @FXML private void submitAppeal(){
        R sel = table.getSelectionModel().getSelectedItem();
        if(sel==null || appealText==null || appealText.getText().trim().isEmpty()){
            new Alert(Alert.AlertType.WARNING, "Select a result row and enter your appeal reason.").show(); return;
        }
        try(Connection c=DatabaseManager.getConnection();
            PreparedStatement ps=c.prepareStatement("INSERT INTO result_appeals(id, result_id, student_id, subject_code, reason) VALUES(?,?,?,?,?)")){
            ps.setObject(1, java.util.UUID.randomUUID());
            ps.setObject(2, java.util.UUID.fromString(sel.resultId));
            ps.setObject(3, java.util.UUID.fromString(AuthService.Session.userId));
            ps.setString(4, sel.subject);
            ps.setString(5, appealText.getText().trim());
            ps.executeUpdate();
            new Alert(Alert.AlertType.INFORMATION, "Appeal submitted. Admin will review. You will be notified.").show();
            appealText.clear();
            AuthService.logAudit("RESULT_APPEAL_SUBMIT", "results", sel.resultId);
        }catch(Exception e){ new Alert(Alert.AlertType.ERROR, e.getMessage()).show(); }
    }
}

package com.femzyk.klc.admin;

import com.femzyk.klc.db.DatabaseManager;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import java.io.FileOutputStream;
import java.sql.*;
import java.util.*;

public class BroadsheetController {
    @FXML private ComboBox<String> classBox, termBox;
    @FXML private TableView<ObservableList<String>> table;
    @FXML private Label status;

    private List<String> subjects = new ArrayList<>();
    private List<StudentRec> students = new ArrayList<>();
    
    @FXML public void initialize(){
        classBox.getItems().addAll("JSS1","JSS2","JSS3","SS1","SS2","SS3");
        termBox.getItems().addAll("1st","2nd","3rd");
        termBox.setValue("1st");
    }
    
    @FXML private void loadBroadsheet(){
        if(classBox.getValue()==null){ status.setText("Pick Class"); return;}
        table.getColumns().clear();
        table.getItems().clear();
        subjects.clear(); students.clear();
        try(Connection c=DatabaseManager.getConnection()){
            try(PreparedStatement ps=c.prepareStatement("SELECT DISTINCT s.subject_code FROM ca_scores cs JOIN subjects s ON s.id=cs.subject_id WHERE cs.class_level=? AND cs.term=? ORDER BY s.subject_code")){
                ps.setString(1, classBox.getValue()); ps.setString(2, termBox.getValue());
                ResultSet rs=ps.executeQuery();
                while(rs.next()) subjects.add(rs.getString(1));
            }
            if(subjects.isEmpty()){
                try(PreparedStatement ps=c.prepareStatement("SELECT subject_code FROM subjects WHERE class_level=? AND is_active=true ORDER BY subject_code")){
                    ps.setString(1, classBox.getValue()); ResultSet rs=ps.executeQuery();
                    while(rs.next()) subjects.add(rs.getString(1));
                }
            }
            String[] cols = new String[3 + subjects.size() + 3];
            cols[0]="S/N"; cols[1]="Admission No"; cols[2]="Name";
            for(int i=0;i<subjects.size();i++) cols[3+i]=subjects.get(i);
            cols[3+subjects.size()]="Total"; cols[4+subjects.size()]="Average"; cols[5+subjects.size()]="Position";
            for(int i=0;i<cols.length;i++){
                final int ci=i;
                TableColumn<ObservableList<String>, String> tc = new TableColumn<>(cols[i]);
                tc.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(cd.getValue().size()>ci?cd.getValue().get(ci):""));
                tc.setPrefWidth(i<3?130:85);
                table.getColumns().add(tc);
            }
            try(PreparedStatement ps=c.prepareStatement("SELECT u.id, sp.admission_no, u.full_name FROM student_profiles sp JOIN users u ON u.id=sp.user_id WHERE sp.class_level=? AND sp.status='ACTIVE' ORDER BY sp.admission_no")){
                ps.setString(1, classBox.getValue());
                ResultSet rs=ps.executeQuery();
                while(rs.next()) students.add(new StudentRec(rs.getString(1), rs.getString(2), rs.getString(3)));
            }
            ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
            int sn=1;
            List<StudentTotal> totals = new ArrayList<>();
            for(StudentRec st: students){
                ObservableList<String> row = FXCollections.observableArrayList();
                row.add(String.valueOf(sn++));
                row.add(st.admission);
                row.add(st.name);
                double sum=0; int cnt=0;
                for(String subCode: subjects){
                    double score = 0;
                    try(PreparedStatement ps=c.prepareStatement("""
                        SELECT cs.total_score FROM ca_scores cs JOIN subjects s ON s.id=cs.subject_id
                        WHERE cs.student_id=? AND s.subject_code=? AND cs.term=? AND cs.session='2024/2025'""")){
                        ps.setObject(1, UUID.fromString(st.id)); ps.setString(2, subCode); ps.setString(3, termBox.getValue());
                        ResultSet rs=ps.executeQuery(); if(rs.next()) score = rs.getDouble(1);
                    }
                    row.add(score==0?"-":String.format("%.0f",score));
                    if(score>0){ sum+=score; cnt++; }
                }
                double avg = cnt>0? sum/cnt : 0;
                row.add(String.format("%.0f",sum));
                row.add(String.format("%.1f",avg));
                row.add("");
                rows.add(row);
                totals.add(new StudentTotal(rows.size()-1, sum));
            }
            totals.sort((a,b)-> Double.compare(b.total, a.total));
            int pos=1;
            for(StudentTotal t: totals){
                ObservableList<String> row = rows.get(t.rowIdx);
                row.set(row.size()-1, String.valueOf(pos++));
            }
            table.setItems(rows);
            status.setText("Broadsheet loaded: "+students.size()+" students, "+subjects.size()+" subjects - "+classBox.getValue()+" "+termBox.getValue()+" Term");
        }catch(Exception e){ status.setText(e.getMessage()); e.printStackTrace(); }
    }

    @FXML private void exportExcel(){
        if(table.getItems().isEmpty()){ status.setText("Load broadsheet first"); return; }
        try(Workbook wb = new XSSFWorkbook()){
            Sheet sh = wb.createSheet(classBox.getValue()+" Broadsheet");
            int r=0;
            Row hr = sh.createRow(r++); hr.createCell(0).setCellValue("KNOWLEDGE LAND COLLEGE");
            Row hr2 = sh.createRow(r++); hr2.createCell(0).setCellValue(classBox.getValue()+" - "+termBox.getValue()+" Term - 2024/2025 - Class Broadsheet");
            r++;
            Row header = sh.createRow(r++);
            for(int c=0;c<table.getColumns().size();c++) header.createCell(c).setCellValue(table.getColumns().get(c).getText());
            for(var rowData: table.getItems()){
                Row row = sh.createRow(r++);
                for(int c=0;c<rowData.size();c++) row.createCell(c).setCellValue(rowData.get(c));
            }
            String fn = "KLC_Broadsheet_"+classBox.getValue()+"_"+termBox.getValue()+".xlsx";
            try(FileOutputStream fos = new FileOutputStream(fn)){ wb.write(fos); }
            status.setText("Exported Excel: "+fn);
            new Alert(Alert.AlertType.INFORMATION, "Broadsheet exported: "+fn).showAndWait();
        }catch(Exception e){ status.setText(e.getMessage()); e.printStackTrace();}
    }

    @FXML private void exportPdf(){
        if(table.getItems().isEmpty()){ status.setText("Load broadsheet first"); return; }
        try{
            String fn = "KLC_Broadsheet_"+classBox.getValue()+"_"+termBox.getValue()+".pdf";
            PdfWriter writer = new PdfWriter(new FileOutputStream(fn));
            PdfDocument pdf = new PdfDocument(writer);
            Document doc = new Document(pdf);
            doc.setFontSize(8);
            doc.add(new Paragraph("KNOWLEDGE LAND COLLEGE").setBold().setTextAlignment(TextAlignment.CENTER));
            doc.add(new Paragraph(classBox.getValue()+" - "+termBox.getValue()+" Term 2024/2025 - Class Broadsheet")
                .setTextAlignment(TextAlignment.CENTER));
            doc.add(new Paragraph(" "));
            int cols = table.getColumns().size();
            float[] widths = new float[cols];
            for(int i=0;i<cols;i++) widths[i] = i<2 ? 2 : i==2 ? 3 : 1;
            Table t = new Table(UnitValue.createPercentArray(widths)).useAllAvailableWidth().setFontSize(7);
            for(int c=0;c<cols;c++) t.addHeaderCell(table.getColumns().get(c).getText());
            for(var row: table.getItems()){
                for(int c=0;c<cols;c++){
                    String v = c < row.size() ? row.get(c) : "";
                    t.addCell(v);
                }
            }
            doc.add(t);
            doc.add(new Paragraph("\nPrincipal: OLUFEMI BENUA KERIPE    ____________________").setFontSize(9));
            doc.close();
            status.setText("Exported PDF: "+fn);
            new Alert(Alert.AlertType.INFORMATION, "Broadsheet PDF: "+fn).showAndWait();
        }catch(Exception e){ status.setText(e.getMessage()); e.printStackTrace(); }
    }

    static class StudentRec { String id, admission, name; StudentRec(String i,String a,String n){id=i;admission=a;name=n;} }
    static class StudentTotal { int rowIdx; double total; StudentTotal(int r, double t){rowIdx=r;total=t;} }
}

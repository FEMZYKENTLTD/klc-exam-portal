package com.femzyk.klc.util;

import com.femzyk.klc.db.DatabaseManager;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.*;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import java.io.File;
import java.io.FileOutputStream;
import java.sql.*;
import java.util.UUID;

public class AttendanceSheetPdf {
    private static final DeviceRgb NAVY = new DeviceRgb(15,31,60);

    public static String generate(String examId, String outPath) throws Exception {
        String subject="?", classLevel="?", term="?", session="2024/2025", title="?";
        try(Connection c=DatabaseManager.getConnection();
            PreparedStatement ps=c.prepareStatement("""
              SELECT s.subject_code, e.class_level, e.term, e.session, e.title
              FROM exams e JOIN subjects s ON s.id=e.subject_id WHERE e.id=?
            """)){
            ps.setObject(1, UUID.fromString(examId));
            ResultSet rs=ps.executeQuery();
            if(rs.next()){ subject=rs.getString(1); classLevel=rs.getString(2); term=rs.getString(3); session=rs.getString(4); title=rs.getString(5); }
        }

        if(outPath==null) outPath = "KLC_Attendance_"+subject.replace('/','_')+"_"+classLevel+".pdf";
        PdfWriter writer = new PdfWriter(new FileOutputStream(outPath));
        PdfDocument pdf = new PdfDocument(writer);
        Document doc = new Document(pdf);
        doc.add(new Paragraph("KNOWLEDGE LAND COLLEGE").setBold().setFontSize(14).setFontColor(NAVY).setTextAlignment(TextAlignment.CENTER));
        doc.add(new Paragraph("CBT EXAM ATTENDANCE SHEET").setBold().setFontSize(12).setTextAlignment(TextAlignment.CENTER));
        doc.add(new Paragraph(subject+" - "+classLevel+" | "+term+" Term, "+session+" | "+title).setTextAlignment(TextAlignment.CENTER).setFontSize(10));
        doc.add(new Paragraph(" "));
        
        Table t = new Table(UnitValue.createPercentArray(new float[]{5, 12, 28, 10, 10, 18, 17})).useAllAvailableWidth().setFontSize(9);
        String[] h = {"S/N","Admission No","Full Name","Class","Arm","Signature","Remarks"};
        for(String hh: h) t.addHeaderCell(new Cell().add(new Paragraph(hh).setBold())
            .setBackgroundColor(NAVY).setFontColor(ColorConstants.WHITE).setTextAlignment(TextAlignment.CENTER));

        int sn=1;
        try(Connection c=DatabaseManager.getConnection();
            PreparedStatement ps=c.prepareStatement("""
              SELECT sp.admission_no, u.full_name, sp.class_level, sp.arm, sp.passport_url
              FROM student_profiles sp JOIN users u ON u.id=sp.user_id
              WHERE sp.class_level=? AND sp.status='ACTIVE'
              ORDER BY sp.admission_no
            """)){
            ps.setString(1, classLevel);
            ResultSet rs=ps.executeQuery();
            while(rs.next()){
                t.addCell(String.valueOf(sn++));
                t.addCell(rs.getString(1));
                t.addCell(rs.getString(2));
                t.addCell(rs.getString(3));
                t.addCell(rs.getString(4)==null?"":rs.getString(4));
                t.addCell(" "); // signature
                t.addCell(" "); // remarks / malpractice
            }
        }
        // Pad to 35 rows for printing
        while(sn <= 35){
            t.addCell(String.valueOf(sn++));
            for(int i=0;i<6;i++) t.addCell(" ");
        }
        doc.add(t);
        doc.add(new Paragraph("\n"));
        doc.add(new Paragraph("Total Present: _________    Total Absent: _________\n\nInvigilator Name: ___________________________    Signature: ____________________    Date: ___________\n\nPrincipal: OLUFEMI BENUA KERIPE    ____________________").setFontSize(10));
        doc.add(new Paragraph("\nMalpractice Incident Log:\n1. ________________________________________________\n2. ________________________________________________\n3. ________________________________________________").setFontSize(9));
        doc.add(new Paragraph("\nPowered by FEMZYK - About page only - No watermark on official documents")
            .setFontSize(7).setTextAlignment(TextAlignment.CENTER).setFontColor(ColorConstants.GRAY));
        doc.close();
        return outPath;
    }
}

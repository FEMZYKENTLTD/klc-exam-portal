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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

public class ReportCardPdf {
    private static final DeviceRgb KLC_NAVY = new DeviceRgb(15,31,60);
    private static final DeviceRgb KLC_GOLD = new DeviceRgb(212,175,55);

    public static void generate(String outPath, String studentName, String admissionNo, String classLevel, String term, String session) throws Exception {
        generateForStudent(null, outPath, term, session);
    }

    public static String generateForStudent(String studentUserId, String outPath, String term, String session) throws Exception {
        String studentName = "Student Name";
        String admissionNo = "KLC/XXX/000";
        String classLevel = "SS2";
        String gender = "";
        String passportUrl = null;
        
        // School branding
        String schoolName = "KNOWLEDGE LAND COLLEGE";
        String schoolMotto = "Knowledge is Power";
        String principalName = "OLUFEMI BENUA KERIPE";
        String schoolLogo = null;
        String principalSig = null;

        try(Connection c = DatabaseManager.getConnection()){
            try(PreparedStatement ps = c.prepareStatement("SELECT school_name, motto, principal_name, logo_url, principal_signature_url FROM school_profile LIMIT 1")){
                ResultSet rs = ps.executeQuery();
                if(rs.next()){
                    schoolName = rs.getString(1) != null ? rs.getString(1) : schoolName;
                    schoolMotto = rs.getString(2) != null ? rs.getString(2) : schoolMotto;
                    principalName = rs.getString(3) != null ? rs.getString(3) : principalName;
                    schoolLogo = rs.getString(4);
                    principalSig = rs.getString(5);
                }
            }
        }catch(Exception ignored){}

        if (studentUserId != null) {
            try(Connection c = DatabaseManager.getConnection();
                PreparedStatement ps = c.prepareStatement(
                    "SELECT u.full_name, sp.admission_no, sp.class_level, sp.gender, sp.passport_url FROM users u JOIN student_profiles sp ON sp.user_id=u.id WHERE u.id=?")) {
                ps.setObject(1, UUID.fromString(studentUserId));
                ResultSet rs = ps.executeQuery();
                if(rs.next()){
                    studentName = rs.getString(1);
                    admissionNo = rs.getString(2);
                    classLevel = rs.getString(3);
                    gender = rs.getString(4)==null?"":rs.getString(4);
                    passportUrl = rs.getString(5);
                }
            }catch(Exception ignored){}
        }

        if(outPath == null) outPath = "KLC_Report_"+admissionNo.replace('/','_')+"_"+term+"_"+session.replace('/','-')+".pdf";

        String qrVerify = "KLC|"+admissionNo+"|"+term+"|"+session+"|VERIFY:https://klc-admin.netlify.app/result-check.html";
        String qrPath = "qr_tmp_"+System.currentTimeMillis()+".png";
        try{ QrUtil.makeQr(qrVerify, qrPath, 120); } catch(Exception e){ qrPath=null; }

        PdfWriter writer = new PdfWriter(new FileOutputStream(outPath));
        PdfDocument pdf = new PdfDocument(writer);
        Document doc = new Document(pdf);

        // Header with logo
        Table header = new Table(UnitValue.createPercentArray(new float[]{15,70,15})).useAllAvailableWidth();
        Cell logoCell = new Cell().setBorder(null).setTextAlignment(TextAlignment.CENTER);
        boolean logoOk = false;
        if(schoolLogo != null && new File(schoolLogo).exists()){
            try{ logoCell.add(new Image(ImageDataFactory.create(schoolLogo)).setAutoScale(true).setMaxHeight(52)); logoOk=true; }catch(Exception ignored){}
        }
        if(!logoOk) logoCell.add(new Paragraph("KLC").setBold().setFontSize(18).setFontColor(KLC_GOLD));
        
        Cell titleCell = new Cell()
            .add(new Paragraph(schoolName.toUpperCase()).setBold().setFontSize(16).setFontColor(KLC_NAVY).setTextAlignment(TextAlignment.CENTER))
            .add(new Paragraph(schoolMotto).setFontSize(9).setItalic().setTextAlignment(TextAlignment.CENTER))
            .add(new Paragraph("OFFICIAL TERM REPORT CARD").setBold().setFontSize(12).setFontColor(KLC_GOLD).setTextAlignment(TextAlignment.CENTER))
            .setBorder(null);
        Cell qrCell = new Cell().setBorder(null).setTextAlignment(TextAlignment.RIGHT);
        if(qrPath != null) {
            try{ qrCell.add(new Image(ImageDataFactory.create(qrPath)).setAutoScale(true)); 
                 qrCell.add(new Paragraph("Scan to Verify").setFontSize(6).setTextAlignment(TextAlignment.CENTER));
            } catch(Exception ignored){}
        }
        header.addCell(logoCell); header.addCell(titleCell); header.addCell(qrCell);
        doc.add(header);
        doc.add(new Paragraph(" ").setFontSize(4));

        // Student info with passport
        Table infoTop = new Table(UnitValue.createPercentArray(new float[]{78,22})).useAllAvailableWidth();
        Table info = new Table(UnitValue.createPercentArray(new float[]{25,25,25,25})).useAllAvailableWidth();
        info.addCell(cell("Name: "+studentName, true));
        info.addCell(cell("Admission No: "+admissionNo, true));
        info.addCell(cell("Class: "+classLevel, true));
        info.addCell(cell("Gender: "+gender, true));
        info.addCell(cell("Term: "+term, false));
        info.addCell(cell("Session: "+session, false));
        info.addCell(cell("No. in Class: -", false));
        info.addCell(cell("Position: -", false));
        Cell infoCell = new Cell().add(info).setBorder(null);
        Cell photoCell = new Cell().setTextAlignment(TextAlignment.CENTER).setBorder(null);
        boolean photoOk = false;
        if(passportUrl != null && new File(passportUrl).exists()){
            try{
                photoCell.add(new Image(ImageDataFactory.create(passportUrl)).setAutoScale(true).setMaxHeight(72));
                photoOk = true;
            }catch(Exception ignored){}
        }
        if(!photoOk) photoCell.add(new Paragraph("[Passport\nPhoto]").setFontSize(7).setBackgroundColor(ColorConstants.LIGHT_GRAY).setTextAlignment(TextAlignment.CENTER).setMinHeight(60));
        infoTop.addCell(infoCell);
        infoTop.addCell(photoCell);
        doc.add(infoTop);
        doc.add(new Paragraph(" ").setFontSize(6));

        // Results table
        Table t = new Table(UnitValue.createPercentArray(new float[]{28,10,10,10,10,10,10,12})).useAllAvailableWidth();
        String[] heads = {"SUBJECT","CA1\n/20","CA2\n/20","EXAM\n/60","TOTAL\n/100","GRADE","POSITION","REMARK"};
        for(String h: heads) t.addHeaderCell(new Cell().add(new Paragraph(h).setBold().setFontSize(9))
            .setBackgroundColor(KLC_NAVY).setFontColor(ColorConstants.WHITE).setTextAlignment(TextAlignment.CENTER));

        double grandTotal = 0; int subjectCount = 0;
        if(studentUserId != null){
            try(Connection c = DatabaseManager.getConnection();
                PreparedStatement ps = c.prepareStatement("""
                  SELECT s.subject_name, cs.ca1_score, cs.ca2_score, cs.exam_score, cs.total_score, cs.grade, cs.position
                  FROM ca_scores cs JOIN subjects s ON s.id=cs.subject_id
                  WHERE cs.student_id=? AND cs.term=? AND cs.session=?
                  ORDER BY s.subject_name
                """)){
                ps.setObject(1, UUID.fromString(studentUserId));
                ps.setString(2, term); ps.setString(3, session);
                ResultSet rs = ps.executeQuery();
                while(rs.next()){
                    subjectCount++;
                    double ca1 = rs.getDouble(2), ca2 = rs.getDouble(3), exam = rs.getDouble(4), total = rs.getDouble(5);
                    grandTotal += total;
                    t.addCell(new Cell().add(new Paragraph(rs.getString(1)).setFontSize(9)));
                    t.addCell(numCell(ca1)); t.addCell(numCell(ca2)); t.addCell(numCell(exam));
                    t.addCell(numCellBold(total));
                    t.addCell(numCellText(rs.getString(6)==null?"-":rs.getString(6)));
                    t.addCell(numCellText(rs.getObject(7)==null?"-":String.valueOf(rs.getInt(7))));
                    t.addCell(numCellText(remarkFor(total)));
                }
            }catch(Exception e){ e.printStackTrace(); }
        }
        if(subjectCount==0 && studentUserId != null){
            try(Connection c=DatabaseManager.getConnection();
                PreparedStatement ps=c.prepareStatement("""
                  SELECT s.subject_name, r.percentage
                  FROM results r JOIN exams e ON e.id=r.exam_id JOIN subjects s ON s.id=e.subject_id
                  WHERE r.student_id=? ORDER BY r.created_at DESC LIMIT 12
                """)){
                ps.setObject(1, UUID.fromString(studentUserId));
                ResultSet rs=ps.executeQuery();
                while(rs.next()){
                    subjectCount++;
                    double total = rs.getDouble(2);
                    grandTotal += total;
                    t.addCell(new Cell().add(new Paragraph(rs.getString(1)).setFontSize(9)));
                    t.addCell(numCell(0)); t.addCell(numCell(0)); t.addCell(numCell(total*0.6));
                    t.addCell(numCellBold(total));
                    t.addCell(numCellText(gradeFor(total)));
                    t.addCell(numCellText("-"));
                    t.addCell(numCellText(remarkFor(total)));
                }
            }catch(Exception ignored){}
        }
        if(subjectCount==0){
            String[][] sample = {{"DATA PROCESSING","18","17","55","90","A1","1","Excellent"},{"MATHEMATICS","16","15","48","79","A1","2","Excellent"}};
            for(String[] r: sample){ for(String v: r) t.addCell(new Cell().add(new Paragraph(v).setFontSize(9)).setTextAlignment(TextAlignment.CENTER)); subjectCount++; grandTotal+=Double.parseDouble(r[4]); }
        }
        doc.add(t);
        double avg = subjectCount>0 ? grandTotal/subjectCount : 0;
        doc.add(new Paragraph(" "));
        Table sum = new Table(UnitValue.createPercentArray(new float[]{25,25,25,25})).useAllAvailableWidth();
        sum.addCell(cell("Total Score: "+String.format("%.1f",grandTotal), true));
        sum.addCell(cell("Average: "+String.format("%.1f%%",avg), true));
        sum.addCell(cell("Grade: "+gradeFor(avg), true));
        sum.addCell(cell("Remark: "+remarkFor(avg), true));
        doc.add(sum);

        doc.add(new Paragraph("\n"));
        doc.add(new Paragraph("Class Teacher's Remark: _________________________________________________").setFontSize(10));
        doc.add(new Paragraph("Principal's Remark: "+(avg>=70?"Excellent performance. Keep it up.":avg>=50?"Good. Can do better.":"Needs improvement.")).setFontSize(10));
        doc.add(new Paragraph("\n"));

        Table sign = new Table(UnitValue.createPercentArray(new float[]{50,50})).useAllAvailableWidth();
        Cell leftSig = new Cell().add(new Paragraph("___________________________\nClass Teacher\nDate: ___________").setFontSize(10)).setBorder(null);
        Cell rightSig = new Cell().setTextAlignment(TextAlignment.RIGHT).setBorder(null);
        boolean sigOk = false;
        if(principalSig != null && new File(principalSig).exists()){
            try{
                rightSig.add(new Image(ImageDataFactory.create(principalSig)).setAutoScale(true).setMaxHeight(28));
                sigOk = true;
            }catch(Exception ignored){}
        }
        if(!sigOk) rightSig.add(new Paragraph("___________________________").setFontSize(10));
        rightSig.add(new Paragraph("Principal: "+principalName+"\nDate: ___________").setFontSize(10));
        sign.addCell(leftSig); sign.addCell(rightSig);
        doc.add(sign);

        doc.add(new Paragraph("\n"));
        Paragraph footer = new Paragraph()
            .add("Result PIN: SURNAME+CLASS  |  Verify: https://klc-admin.netlify.app/result-check.html\n")
            .add(schoolName.toUpperCase()+" - "+schoolMotto)
            .setFontSize(8).setTextAlignment(TextAlignment.CENTER).setFontColor(ColorConstants.GRAY);
        doc.add(footer);

        doc.close();
        if(qrPath != null) try{ new java.io.File(qrPath).delete(); } catch(Exception ignored){}
        return outPath;
    }

    private static Cell cell(String t, boolean bold){
        Paragraph p = new Paragraph(t).setFontSize(10);
        if(bold) p.setBold();
        return new Cell().add(p);
    }
    private static Cell numCell(double v){ return new Cell().add(new Paragraph(v==0?"-":String.format("%.0f",v)).setFontSize(9)).setTextAlignment(TextAlignment.CENTER); }
    private static Cell numCellBold(double v){ return new Cell().add(new Paragraph(String.format("%.0f",v)).setBold().setFontSize(9)).setTextAlignment(TextAlignment.CENTER); }
    private static Cell numCellText(String s){ return new Cell().add(new Paragraph(s).setFontSize(9)).setTextAlignment(TextAlignment.CENTER); }
    private static String gradeFor(double total){
        if(total>=75) return "A1"; if(total>=70) return "B2"; if(total>=65) return "B3";
        if(total>=60) return "C4"; if(total>=55) return "C5"; if(total>=50) return "C6";
        if(total>=45) return "D7"; if(total>=40) return "E8"; return "F9";
    }
    private static String remarkFor(double total){
        if(total>=75) return "Excellent";
        if(total>=60) return "Very Good";
        if(total>=50) return "Good";
        if(total>=40) return "Pass";
        return "Fail";
    }
}

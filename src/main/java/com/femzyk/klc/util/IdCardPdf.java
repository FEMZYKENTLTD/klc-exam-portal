package com.femzyk.klc.util;

import com.femzyk.klc.db.DatabaseManager;
import com.itextpdf.io.image.ImageData;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.geom.PageSize;
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

public class IdCardPdf {
    private static final DeviceRgb NAVY = new DeviceRgb(15,31,60);

    public static String generate(String admissionNo, String fullName, String classLevel, String session) throws Exception {
        // Try to load passport photo + school logo from DB / files
        String passportPath = null;
        String schoolLogo = null;
        String principalSig = null;
        try(Connection c = DatabaseManager.getConnection()){
            // student passport
            try(PreparedStatement ps=c.prepareStatement("SELECT sp.passport_url FROM student_profiles sp WHERE sp.admission_no=?")){
                ps.setString(1, admissionNo);
                ResultSet rs=ps.executeQuery();
                if(rs.next()) passportPath = rs.getString(1);
            }
            // school branding
            try(PreparedStatement ps=c.prepareStatement("SELECT logo_url, principal_signature_url FROM school_profile LIMIT 1")){
                ResultSet rs=ps.executeQuery();
                if(rs.next()){ schoolLogo = rs.getString(1); principalSig = rs.getString(2); }
            }catch(Exception ignored){}
        }catch(Exception ignored){}

        // QR verification
        String qrText = "KLC|"+admissionNo+"|"+fullName+"|"+classLevel+"|VERIFY:https://klc-admin.netlify.app/result-check.html";
        String qrPath = "qr_id_"+admissionNo.replace('/','_')+".png";
        QrUtil.makeQr(qrText, qrPath, 180);

        String out = "KLC_ID_"+admissionNo.replace('/','_')+".pdf";
        PdfWriter writer = new PdfWriter(new FileOutputStream(out));
        PdfDocument pdf = new PdfDocument(writer);
        pdf.setDefaultPageSize(new PageSize(242, 153)); // CR80
        Document doc = new Document(pdf);
        doc.setMargins(6,6,6,6);

        Table t = new Table(UnitValue.createPercentArray(new float[]{38,62})).useAllAvailableWidth();

        // Left column: Logo + Photo + QR
        Cell left = new Cell().setTextAlignment(TextAlignment.CENTER);
        // School logo
        if(schoolLogo != null && new File(schoolLogo).exists()){
            try{ left.add(new Image(ImageDataFactory.create(schoolLogo)).setAutoScale(true).setMaxHeight(28)); } catch(Exception ignored){}
        } else {
            left.add(new Paragraph("KLC").setBold().setFontSize(12).setFontColor(NAVY));
        }
        left.add(new Paragraph("KNOWLEDGE\nLAND\nCOLLEGE").setFontSize(6));
        // Passport photo
        boolean photoAdded = false;
        if(passportPath != null && new File(passportPath).exists()){
            try{
                ImageData pImg = ImageDataFactory.create(passportPath);
                left.add(new Image(pImg).setAutoScale(true).setMaxHeight(55));
                photoAdded = true;
            }catch(Exception ignored){}
        }
        if(!photoAdded){
            left.add(new Paragraph("[  PHOTO  ]\n\n").setFontSize(7).setBackgroundColor(ColorConstants.LIGHT_GRAY));
        }
        // QR
        left.add(new Image(ImageDataFactory.create(qrPath)).setAutoScale(true).setMaxHeight(45));
        left.add(new Paragraph("Scan to Verify").setFontSize(5));

        // Right column: Details
        Cell right = new Cell()
            .add(new Paragraph(fullName.toUpperCase()).setBold().setFontSize(10))
            .add(new Paragraph("Admission No: "+admissionNo).setFontSize(7))
            .add(new Paragraph("Class: "+classLevel).setFontSize(7))
            .add(new Paragraph("Session: "+(session==null?"2024/2025":session)).setFontSize(7))
            .add(new Paragraph("\nSTUDENT").setBold().setFontColor(new DeviceRgb(20,70,160)).setFontSize(9));
        // Principal signature
        if(principalSig != null && new File(principalSig).exists()){
            try{
                right.add(new Image(ImageDataFactory.create(principalSig)).setAutoScale(true).setMaxHeight(18));
            }catch(Exception ignored){}
        }
        right.add(new Paragraph("Principal: OLUFEMI BENUA KERIPE").setFontSize(5));

        t.addCell(left); t.addCell(right);
        doc.add(t);
        doc.close();
        new File(qrPath).delete();
        return out;
    }

    // Generate with userId lookup - pulls passport from DB
    public static String generateForStudent(String studentUserId) throws Exception {
        String admission="KLC/XXX/000", name="Student", classLevel="SS2", session="2024/2025";
        try(Connection c=DatabaseManager.getConnection();
            PreparedStatement ps=c.prepareStatement("SELECT sp.admission_no, u.full_name, sp.class_level FROM student_profiles sp JOIN users u ON u.id=sp.user_id WHERE u.id=?")){
            ps.setObject(1, UUID.fromString(studentUserId));
            ResultSet rs=ps.executeQuery();
            if(rs.next()){ admission=rs.getString(1); name=rs.getString(2); classLevel=rs.getString(3); }
        }catch(Exception ignored){}
        return generate(admission, name, classLevel, session);
    }
}

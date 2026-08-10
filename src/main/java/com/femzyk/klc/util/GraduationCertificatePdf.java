package com.femzyk.klc.util;

import com.femzyk.klc.db.DatabaseManager;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.properties.TextAlignment;
import java.io.FileOutputStream;
import java.sql.*;
import java.util.UUID;

public class GraduationCertificatePdf {
    private static final DeviceRgb NAVY = new DeviceRgb(15,31,60);
    private static final DeviceRgb GOLD = new DeviceRgb(212,175,55);

    public static String generate(String studentUserId, String outPath) throws Exception {
        String name="Student", admission="", classLevel="SS3";
        try(Connection c=DatabaseManager.getConnection();
            PreparedStatement ps=c.prepareStatement(
                "SELECT u.full_name, sp.admission_no, sp.class_level FROM users u JOIN student_profiles sp ON sp.user_id=u.id WHERE u.id=?")){
            ps.setObject(1, UUID.fromString(studentUserId));
            ResultSet rs=ps.executeQuery();
            if(rs.next()){ name=rs.getString(1); admission=rs.getString(2); classLevel=rs.getString(3); }
        }catch(Exception ignored){}
        if(outPath==null) outPath = "KLC_GRADUATION_"+admission.replace('/','_')+".pdf";
        PdfWriter writer = new PdfWriter(new FileOutputStream(outPath));
        PdfDocument pdf = new PdfDocument(writer);
        Document doc = new Document(pdf);
        doc.add(new Paragraph("\n\n\n"));
        doc.add(new Paragraph("KNOWLEDGE LAND COLLEGE").setBold().setFontSize(22).setFontColor(NAVY).setTextAlignment(TextAlignment.CENTER));
        doc.add(new Paragraph("Secondary School - Lagos, Kwara State").setFontSize(11).setTextAlignment(TextAlignment.CENTER));
        doc.add(new Paragraph("\n"));
        doc.add(new Paragraph("CERTIFICATE OF COMPLETION").setBold().setFontSize(18).setFontColor(GOLD).setTextAlignment(TextAlignment.CENTER));
        doc.add(new Paragraph("\n\n"));
        doc.add(new Paragraph("This is to certify that").setFontSize(13).setTextAlignment(TextAlignment.CENTER));
        doc.add(new Paragraph(name.toUpperCase()).setBold().setFontSize(20).setTextAlignment(TextAlignment.CENTER));
        doc.add(new Paragraph("Admission No: "+admission).setFontSize(12).setTextAlignment(TextAlignment.CENTER));
        doc.add(new Paragraph("\nhas successfully completed the Secondary School Education\nat KNOWLEDGE LAND COLLEGE\nand is hereby awarded this certificate.\n").setFontSize(13).setTextAlignment(TextAlignment.CENTER));
        doc.add(new Paragraph("\n\n\n"));
        doc.add(new Paragraph("___________________________\nOLUFEMI BENUA KERIPE\nPrincipal\nKNOWLEDGE LAND COLLEGE\nDate: ___________")
            .setTextAlignment(TextAlignment.CENTER).setFontSize(11));
        doc.add(new Paragraph("\n\nKnowledge is Power").setItalic().setTextAlignment(TextAlignment.CENTER).setFontColor(ColorConstants.GRAY));
        doc.add(new Paragraph("Verify at: https://klc-admin.netlify.app/result-check.html")
            .setFontSize(8).setTextAlignment(TextAlignment.CENTER).setFontColor(ColorConstants.GRAY));
        doc.close();
        return outPath;
    }
}

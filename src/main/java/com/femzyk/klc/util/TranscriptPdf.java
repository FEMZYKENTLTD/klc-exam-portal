package com.femzyk.klc.util;

import java.io.FileOutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.femzyk.klc.db.DatabaseManager;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;

/**
 * Enterprise Transcript PDF Generator - KLC CBT Suite v1.0
 */
public class TranscriptPdf {

    private static final DeviceRgb NAVY = new DeviceRgb(15, 31, 60);
    private static final DeviceRgb GOLD = new DeviceRgb(212, 175, 55);

    public static String generateCumulativeTranscript(String studentUserId, String outPath) throws Exception {

        String name = "Student", admission = "", classLevel = "";

        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT u.full_name, sp.admission_no, sp.class_level FROM users u " +
                 "JOIN student_profiles sp ON sp.user_id = u.id WHERE u.id = ?")) {

            ps.setObject(1, UUID.fromString(studentUserId));
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                name = rs.getString(1);
                admission = rs.getString(2);
                classLevel = rs.getString(3);
            }
        }

        if (outPath == null) {
            outPath = "KLC_TRANSCRIPT_" + admission.replace('/', '_') + ".pdf";
        }

        PdfWriter writer = new PdfWriter(new FileOutputStream(outPath));
        PdfDocument pdf = new PdfDocument(writer);
        Document doc = new Document(pdf);

        // Header
        doc.add(new Paragraph("KNOWLEDGE LAND COLLEGE")
            .setBold().setFontSize(16).setFontColor(NAVY).setTextAlignment(TextAlignment.CENTER));
        doc.add(new Paragraph("Secondary School - Lagos, Nigeria")
            .setFontSize(9).setTextAlignment(TextAlignment.CENTER));
        doc.add(new Paragraph("OFFICIAL CUMULATIVE TRANSCRIPT")
            .setBold().setFontSize(13).setFontColor(GOLD).setTextAlignment(TextAlignment.CENTER));
        doc.add(new Paragraph(" "));

        doc.add(new Paragraph("Name: " + name + "    Admission No: " + admission + "    Class: " + classLevel)
            .setFontSize(10));

        // Terms & Sessions
        String[] terms = {"1st", "2nd", "3rd"};
        String[] sessions = {"2024/2025"};

        // Try to get real sessions from database
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT DISTINCT session FROM ca_scores WHERE student_id = ? ORDER BY session")) {

            ps.setObject(1, UUID.fromString(studentUserId));
            ResultSet rs = ps.executeQuery();
            List<String> s = new ArrayList<>();
            while (rs.next()) s.add(rs.getString(1));
            if (!s.isEmpty()) sessions = s.toArray(new String[0]);
        } catch (Exception ignored) {}

        double grandTotal = 0;
        int grandCount = 0;

        for (String session : sessions) {
            for (String term : terms) {

                List<Record> recs = new ArrayList<>();

                try (Connection c = DatabaseManager.getConnection();
                     PreparedStatement ps = c.prepareStatement(
                         "SELECT s.subject_name, cs.ca1_score, cs.ca2_score, cs.exam_score, " +
                         "cs.total_score, cs.grade " +
                         "FROM ca_scores cs JOIN subjects s ON s.id = cs.subject_id " +
                         "WHERE cs.student_id = ? AND cs.term = ? AND cs.session = ? " +
                         "ORDER BY s.subject_name")) {

                    ps.setObject(1, UUID.fromString(studentUserId));
                    ps.setString(2, term);
                    ps.setString(3, session);
                    ResultSet rs = ps.executeQuery();

                    while (rs.next()) {
                        recs.add(new Record(
                            rs.getString(1),
                            rs.getDouble(2),
                            rs.getDouble(3),
                            rs.getDouble(4),
                            rs.getDouble(5),
                            rs.getString(6)
                        ));
                    }
                } catch (Exception ignored) {}

                if (recs.isEmpty()) continue;

                doc.add(new Paragraph(session + " - " + term + " Term")
                    .setBold().setFontSize(11).setMarginTop(8));

                Table t = new Table(UnitValue.createPercentArray(new float[]{30, 12, 12, 12, 12, 10, 12}))
                    .useAllAvailableWidth();

                String[] h = {"Subject", "CA1", "CA2", "Exam", "Total", "Grade", "Remark"};
                for (String hh : h) {
                    t.addHeaderCell(new Cell()
                        .add(new Paragraph(hh).setBold().setFontSize(9))
                        .setBackgroundColor(NAVY)
                        .setFontColor(ColorConstants.WHITE)
                        .setTextAlignment(TextAlignment.CENTER));
                }

                double termTotal = 0;
                for (Record r : recs) {
                    t.addCell(cell(r.subject, false));
                    t.addCell(num(r.ca1));
                    t.addCell(num(r.ca2));
                    t.addCell(num(r.exam));
                    t.addCell(numBold(r.total));
                    t.addCell(numText(r.grade));
                    t.addCell(numText(remark(r.total)));
                    termTotal += r.total;
                }

                doc.add(t);

                double avg = recs.isEmpty() ? 0 : termTotal / recs.size();
                grandTotal += termTotal;
                grandCount += recs.size();

                doc.add(new Paragraph(String.format("Term Average: %.1f%%   Grade: %s", avg, grade(avg)))
                    .setFontSize(10).setBold());
            }
        }

        double cgpa = grandCount > 0 ? grandTotal / grandCount : 0;

        doc.add(new Paragraph("\n"));
        doc.add(new Paragraph(String.format("CUMULATIVE AVERAGE (CGPA): %.2f%%   -   %s", cgpa, grade(cgpa)))
            .setBold().setFontSize(12));

        doc.add(new Paragraph("\n\n"));
        doc.add(new Paragraph("___________________________\nPrincipal: OLUFEMI BENUA KERIPE\nKNOWLEDGE LAND COLLEGE\nDate: ___________")
            .setFontSize(10));

        doc.add(new Paragraph("\nThis transcript is official and valid without alteration. Verify at: https://klc-admin.netlify.app/result-check.html")
            .setFontSize(8).setTextAlignment(TextAlignment.CENTER).setFontColor(ColorConstants.GRAY));

        doc.add(new Paragraph("Powered by FEMZYK - About page only - No watermark on official results")
            .setFontSize(7).setTextAlignment(TextAlignment.CENTER).setFontColor(ColorConstants.GRAY));

        doc.close();
        return outPath;
    }

    // ==================== Helper Classes & Methods ====================

    static class Record {
        String subject, grade;
        double ca1, ca2, exam, total;

        Record(String s, double c1, double c2, double e, double t, String g) {
            subject = s; ca1 = c1; ca2 = c2; exam = e; total = t; grade = g;
        }
    }

    private static Cell cell(String t, boolean bold) {
        Paragraph p = new Paragraph(t).setFontSize(9);
        if (bold) p.setBold();
        return new Cell().add(p);
    }

    private static Cell num(double v) {
        return new Cell().add(new Paragraph(v == 0 ? "-" : String.format("%.0f", v))
            .setFontSize(9)).setTextAlignment(TextAlignment.CENTER);
    }

    private static Cell numBold(double v) {
        return new Cell().add(new Paragraph(String.format("%.0f", v))
            .setBold().setFontSize(9)).setTextAlignment(TextAlignment.CENTER);
    }

    private static Cell numText(String s) {
        return new Cell().add(new Paragraph(s == null ? "-" : s)
            .setFontSize(9)).setTextAlignment(TextAlignment.CENTER);
    }

    private static String grade(double t) {
        if (t >= 75) return "A1";
        if (t >= 70) return "B2";
        if (t >= 65) return "B3";
        if (t >= 60) return "C4";
        if (t >= 55) return "C5";
        if (t >= 50) return "C6";
        if (t >= 45) return "D7";
        if (t >= 40) return "E8";
        return "F9";
    }

    private static String remark(double t) {
        if (t >= 75) return "Excellent";
        if (t >= 60) return "Very Good";
        if (t >= 50) return "Good";
        if (t >= 40) return "Pass";
        return "Fail";
    }
}
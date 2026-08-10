package com.femzyk.klc.util;

public class ReportCardService {

    public static String generateReportCard(String studentUserId, String term, String session) {
        try {
            String outPath = "ReportCard_" + studentUserId.replace("-", "") + "_" + term + "_" + session.replace("/", "-") + ".pdf";
            return ReportCardPdf.generateForStudent(studentUserId, outPath, term, session);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static String generateTranscript(String studentUserId) {
        try {
            String outPath = "Transcript_" + studentUserId.replace("-", "") + ".pdf";
            return ReportCardPdf.generateForStudent(studentUserId, outPath, "All Terms", "Cumulative");
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
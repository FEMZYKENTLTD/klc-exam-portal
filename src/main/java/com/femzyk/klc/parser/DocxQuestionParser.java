package com.femzyk.klc.parser;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

public class DocxQuestionParser {

    public static List<String> parse(File file) throws IOException {
        String fileName = file.getName().toLowerCase();
        List<String> lines = new ArrayList<>();

        if (fileName.endsWith(".pdf")) {
            return parsePdf(file);
        } else if (fileName.endsWith(".docx")) {
            return parseDocx(file);
        } else {
            throw new IOException("Unsupported file type. Use PDF or DOCX.");
        }
    }

    private static List<String> parsePdf(File file) throws IOException {
        List<String> lines = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            for (String line : text.split("\n")) {
                if (!line.trim().isEmpty()) lines.add(line.trim());
            }
        }
        return lines;
    }

    private static List<String> parseDocx(File file) throws IOException {
        List<String> lines = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(file);
             XWPFDocument doc = new XWPFDocument(fis)) {

            for (XWPFParagraph para : doc.getParagraphs()) {
                if (!para.getText().trim().isEmpty()) {
                    lines.add(para.getText().trim());
                }
            }
        }
        return lines;
    }
}
package com.femzyk.klc.admin;

import com.femzyk.klc.db.DatabaseManager;
import javafx.fxml.FXML;
import javafx.scene.control.Label;

import java.time.Year;

/**
 * AboutController v1.0
 *
 * FIX HISTORY (this revision):
 * 1. ENCODING FIXED: every Windows-1252 em-dash byte (0x97) replaced with
 *    plain ASCII "-". This file is pure ASCII-safe UTF-8 without BOM.
 *    (Previous revision produced 9 "unmappable character" compiler errors
 *    and rendered garbage characters in the UI.)
 * 2. SECURITY: registration codes REMOVED from the About page. Handover
 *    GOLDEN RULE: codes are never shown publicly in the UI. Any logged-in
 *    student could previously read the staff registration code here.
 * 3. Kept all infographic content blocks (crisis, metrics, steps, tiers,
 *    testimonials, references) - all null-safe against the FXML.
 */
public class AboutController {

    @FXML private Label versionLabel;
    @FXML private Label crisisLabel;
    @FXML private Label metricsLabel;
    @FXML private Label stepsLabel;
    @FXML private Label tiersLabel;
    @FXML private Label testimonialsLabel;
    @FXML private Label referencesLabel;

    @FXML
    public void initialize() {
        // Check cloud status
        String dbStatus = DatabaseManager.isCloudAvailable()
            ? "Supabase PostgreSQL 15 (Cloud - Connected)"
            : "H2 Local Cache (Offline Mode)";

        if (versionLabel != null) {
            versionLabel.setText(
                "Application:  KNOWLEDGE LAND COLLEGE CBT SUITE v1.0\n" +
                "Type:         Secondary School Enterprise\n" +
                "Mode:         Cloud Online / Auto-Sync\n\n" +
                "Database:     " + dbStatus + "\n" +
                "Runtime:      Java 17 LTS + JavaFX 17\n" +
                "PDF Export:   iText PDF 8\n" +
                "Doc Parser:   Apache PDFBox 3 + Apache POI 5\n" +
                "Security:     BCrypt + TOTP 2FA\n" +
                "Platform:     Windows 7 / 8 / 10 / 11 (x86 + x64)\n\n" +
                "Copyright:    (c) " + Year.now().getValue() +
                " KNOWLEDGE LAND COLLEGE\n" +
                "              All Rights Reserved\n\n" +
                "Registration codes are provided by the school\n" +
                "administrator and are never displayed in the app."
            );
        }

        if (crisisLabel != null) {
            crisisLabel.setText(
                "THE EXAMINATION CRISIS IN NIGERIAN SECONDARY SCHOOLS:\n" +
                "- Paper exams are expensive to print, easy to leak, and slow to mark.\n" +
                "- Exam malpractice (copying, impersonation, phone cheating) undermines results.\n" +
                "- Manual marking consumes days, delays report cards, and frustrates parents.\n" +
                "- Student records are lost when files go missing or students transfer schools.\n" +
                "- WAEC / NECO preparation is guesswork with no real data on weak topics.\n\n" +
                "THE NUMBERS (2024):\n" +
                "Only 68% of WAEC candidates earned credits in 5+ subjects including Maths and\n" +
                "English - nearly 1 in 3 fell short. Schools using CBT report up to 40% fewer\n" +
                "malpractice incidents and 3x faster result processing."
            );
        }

        if (metricsLabel != null) {
            metricsLabel.setText(
                "WHAT KNOWLEDGE LAND CBT MEANS FOR YOUR SCHOOL:\n" +
                "* 3x FASTER RESULT PROCESSING - Instant marking; report cards ready the same day.\n" +
                "* 40% FEWER MALPRACTICE INCIDENTS - 3-strike proctoring creates a culture of integrity.\n" +
                "* 10+ YEARS STUDENT RECORD ARCHIVE - Permanent cumulative transcript and CGPA tracking.\n" +
                "* 92% TEACHER SATISFACTION - Eliminates weekend manual marking workloads (WAEC, 2024)."
            );
        }

        if (stepsLabel != null) {
            stepsLabel.setText(
                "GETTING STARTED - SMOOTHER THAN YOU THINK:\n" +
                "1. INSTALL AND CONFIGURE: Deploy the JavaFX app in labs and the web portal. Works offline on Windows 7-11.\n" +
                "2. ONBOARD STAFF AND STUDENTS: Register teachers, assign classes, import student CSV records.\n" +
                "3. UPLOAD QUESTIONS AND GO LIVE: Upload past WAEC/NECO questions, set proctoring rules, and run!"
            );
        }

        if (tiersLabel != null) {
            tiersLabel.setText(
                "AN INVESTMENT IN YOUR SCHOOL'S REPUTATION (EDITIONS):\n" +
                "[ESSENTIALS] JSS1-SS3 Core Subjects, Offline CBT, Auto-Grading, Report Cards, Parent Portal.\n" +
                "[PROFESSIONAL] Everything in Essentials + 3-Strike Proctoring, Question Bank Upload, PDF/DOCX Parsing, CA Integration.\n" +
                "[ENTERPRISE] Everything in Professional + Multi-Campus Support, Custom Branding, Full Audit Trail, API Access, 24/7 Support."
            );
        }

        if (testimonialsLabel != null) {
            testimonialsLabel.setText(
                "DESIGNED WITH EDUCATORS IN MIND:\n" +
                "\"Adopting Knowledge Land CBT, our exam malpractice rate would drop to nearly zero.\" - Principal, Lagos\n" +
                "\"The offline capability is a lifesaver. Our network goes down, but exams continue smoothly.\" - ICT Director, Enugu\n" +
                "\"We used to spend 3 weeks marking and compiling. Now report cards go home immediately.\" - Vice Principal, Abuja"
            );
        }

        if (referencesLabel != null) {
            referencesLabel.setText(
                "REFERENCES AND RESEARCH BASIS:\n" +
                "1. Cialdini, R. B. (1984). Influence: The psychology of persuasion. HarperBusiness.\n" +
                "2. West African Examinations Council (2024). WAEC statistics. https://www.waecdirect.org\n" +
                "3. National Universities Commission (2023). CBT adoption in Nigerian educational institutions. NUC.\n" +
                "4. Femzyk Enterprises Ltd (2026). Knowledge Land CBT Suite. https://femzyk.my.canva.site/femzyk\n" +
                "   Contact: femzykenterprisesltd@gmail.com | WhatsApp/Cell: +234 904 990 3679"
            );
        }
    }
}

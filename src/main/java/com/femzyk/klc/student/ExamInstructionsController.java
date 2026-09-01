package com.femzyk.klc.student;

import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.db.DatabaseManager;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;
import java.io.File;
import java.sql.*;

public class ExamInstructionsController {
    @FXML private Label examTitleLabel, subjectLabel, durationLabel, questionsLabel, rulesLabel;
    @FXML private CheckBox acceptCheck;
    @FXML private Button startBtn;
    @FXML private ImageView photoView;

    private String examId;
    private String variant = "A";

    public void init(String examId, String variant){
        this.examId = examId;
        if(variant != null) this.variant = variant;
        loadExam();
        loadStudentPhoto();
    }

    /** KLC v1.0 (spec 5.3): photo verification popup - show the
     *  registered passport so invigilator/student can confirm identity. */
    private void loadStudentPhoto(){
        if (photoView == null) return;
        try(Connection c = DatabaseManager.getConnection();
            PreparedStatement ps = c.prepareStatement(
                "SELECT passport_url FROM student_profiles WHERE user_id=?")){
            AuthService.setUuid(ps, 1, AuthService.Session.userId, c);
            ResultSet rs = ps.executeQuery();
            if(rs.next()){
                String p = rs.getString(1);
                if(p != null && !p.isBlank()){
                    File f = new File(p);
                    if(f.exists())
                        photoView.setImage(new Image(
                            f.toURI().toString(), 120, 140, true, true));
                }
            }
        }catch(Exception ignored){ /* photo is optional - never block */ }
    }

    private void loadExam(){
        try(Connection c = DatabaseManager.getConnection();
            PreparedStatement ps = c.prepareStatement("""
              SELECT s.subject_code, e.class_level, e.duration_minutes, e.instructions, e.negative_marking,
                     (SELECT COUNT(*) FROM exam_questions WHERE exam_id=e.id) AS qcount
              FROM exams e JOIN subjects s ON s.id=e.subject_id WHERE e.id=?
            """)){
            // KLC v1.0 FIX: cross-DB UUID bind (was setObject(UUID) - broke H2)
            AuthService.setUuid(ps, 1, examId, c);
            ResultSet rs = ps.executeQuery();
            if(rs.next()){
                examTitleLabel.setText(rs.getString(1) + " - " + rs.getString(2) + "  [Variant "+variant+"]");
                subjectLabel.setText("Subject: " + rs.getString(1));
                durationLabel.setText("Duration: " + rs.getInt(3) + " minutes");
                // KLC v1.0 FIX: qcount is column 6 (was reading column 5 =
                // negative_marking, so the screen always showed 0 questions)
                questionsLabel.setText("Questions: " + rs.getInt(6));
                String instr = rs.getString(4);
                if(instr == null || instr.isBlank()) instr = "Answer all questions. No malpractice.";
                double neg = rs.getDouble(5);
                String rules = "KNOWLEDGE LAND COLLEGE - CBT EXAM RULES\n\n" +
                    "1. " + instr + "\n\n" +
                    "2. 3-Strike Proctoring Active:\n   Strike 1 = Warning\n   Strike 2 = Final Warning\n   Strike 3 = Auto-submit + Lockout\n\n" +
                    "3. Do NOT minimize / Alt-Tab / switch apps\n" +
                    "4. Clipboard / Copy-Paste is blocked\n" +
                    "5. Webcam proctoring may be active\n" +
                    "6. Auto-save every 30 seconds - power outage safe\n" +
                    "7. Use Question Navigator to jump / Flag for Review\n" +
                    "8. Calculator and Formula Sheet available in toolbar\n" +
                    "9. Accessibility: Font A+/A-, High Contrast, Dyslexic font\n" +
                    "10. Keyboard: N=Next, P=Previous, F=Flag, 1-5=A-E\n" +
                    (neg > 0 ? "\n⚠️ NEGATIVE MARKING: -"+neg+" per wrong answer\n" : "\nNo negative marking\n") +
                    "\nBy clicking 'I Accept & Start Exam' you agree to abide by all KLC examination rules.\nMalpractice will result in automatic submission and account lockout.\n\nGood luck!\n\nPrincipal: OLUFEMI BENUA KERIPE";
                rulesLabel.setText(rules);
            }
        }catch(Exception e){ e.printStackTrace(); }
    }

    @FXML private void onAcceptChanged(){
        startBtn.setDisable(!acceptCheck.isSelected());
    }

    @FXML private void startExam() throws Exception {
        if(!acceptCheck.isSelected()) return;
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/exam.fxml"));
        Scene scene = new Scene(loader.load(), 1200, 750);
        scene.getStylesheets().add(getClass().getResource("/css/klc-premium.css").toExternalForm());
        Stage st = (Stage) startBtn.getScene().getWindow();
        st.setScene(scene);
        ExamController ec = loader.getController();
        ec.startExam(examId, variant);
    }

    @FXML private void cancel() throws Exception {
        // back to student dashboard
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/student_dashboard.fxml"));
        Scene scene = new Scene(loader.load(), 1150, 720);
        scene.getStylesheets().add(getClass().getResource("/css/klc-premium.css").toExternalForm());
        Stage st = (Stage) startBtn.getScene().getWindow();
        st.setScene(scene);
        st.setFullScreen(false);
    }
}

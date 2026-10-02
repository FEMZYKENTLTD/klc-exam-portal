import com.femzyk.klc.MainApp;
import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.db.DatabaseManager;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.Node;
import javafx.scene.control.TextField;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * KLC CBT Suite - headless interface screenshot harness.
 *
 * Boots the REAL application (MainApp) on the Monocle/Headless graphics
 * pipeline (pure software rendering - no display, X11 or GTK required),
 * seeds a demo school dataset into the offline H2 cache, then walks the
 * FXML interfaces exactly as the controllers do and writes a PNG of each
 * scene. Used to (a) verify the UI pixel-level (previously a documented
 * gap: "pixel-level verification needs a desktop run") and (b) publish
 * real interface screenshots in the repository docs.
 *
 * Run (fat JAR, JDK 17+):
 *   java -cp harness:klc.jar \
 *     -Dglass.platform=Monocle -Dmonocle.platform=Headless \
 *     -Dprism.order=sw -Dmonocle.screen.width=1440 -Dmonocle.screen.height=900 \
 *     KlcShotHarness <output-dir>
 *
 * App config comes from ./config.properties (the AppConfig external
 * overlay) - the harness never touches repository secrets.
 */
public class KlcShotHarness {

    static final int W = 1440, H = 900;
    static String outDir = "screenshots";
    static PrintStream log;
    static int failures = 0;

    public static void main(String[] args) throws Exception {
        if (args.length > 0) outDir = args[0];
        new File(outDir).mkdirs();
        log = new PrintStream(new FileOutputStream(new File(outDir, "_harness.log")), true);

        // 1. Boot the real app on the headless pipeline.
        Thread launcher = new Thread(() -> Application.launch(MainApp.class));
        launcher.setDaemon(true);
        launcher.start();

        long deadline = System.currentTimeMillis() + 120_000;
        while ((MainApp.primaryStage == null
                || MainApp.primaryStage.getScene() == null)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        if (MainApp.primaryStage == null) {
            fail("primaryStage never appeared");
            System.exit(2);
        }
        System.out.println("[harness] app booted - DB ready");

        // 2. Splash, then the auto-move to login.
        shot("01_splash");
        Thread.sleep(3200);          // SplashController advances after ~2s
        fillLoginFields();
        shot("02_login");
        theme(true);
        shot("03_login_dark");
        theme(false);

        // 3. Demo dataset (plain H2 JDBC + the app's own AuthService).
        seed();

        // 4. Register + password reset screens.
        show("register.fxml");
        shot("04_register");
        show("password_reset.fxml");
        shot("05_password_reset");

        // 5. Admin hub + every admin screen (Session = SUPER_ADMIN).
        asSuperAdmin();
        String[] adminScreens = {
            "admin_dashboard.fxml", "admin/admin_home.fxml", "admin/student_manager.fxml",
            "admin/teacher_manager.fxml", "admin/class_manager.fxml", "admin/subject_manager.fxml",
            "admin/question_bank.fxml", "admin/question_editor.fxml", "admin/question_importer.fxml",
            "admin/exam_manager.fxml", "admin/live_monitor.fxml", "admin/results_view.fxml",
            "admin/analytics.fxml", "admin/broadsheet.fxml", "admin/ca_scores.fxml",
            "admin/grading_scale.fxml", "admin/id_cards.fxml", "admin/notifications.fxml",
            "admin/study_materials.fxml", "admin/school_settings.fxml", "admin/health_monitor.fxml",
            "admin/backup.fxml", "admin/audit_logs.fxml", "admin/appeals.fxml",
            "admin/manual_user_creation.fxml", "admin/teacher_import.fxml", "admin/about.fxml"
        };
        int n = 10;
        for (String s : adminScreens) {
            show(s);
            shot(String.format("%02d_%s", n++, s.replace('/', '_').replace(".fxml", "")));
        }

        // Dark theme on the two most-used admin screens.
        theme(true);
        show("admin_dashboard.fxml");
        shot("37_admin_dashboard_dark");
        show("admin/student_manager.fxml");
        shot("38_student_manager_dark");
        theme(false);

        // 6. Student experience.
        asStudent1();
        show("student_dashboard.fxml");
        shot("39_student_dashboard");

        show("practice_exam.fxml");
        shot("39b_practice_exam");

        startSeededExam();
        shot("40_exam_screen");

        // 7. Student social screens (Session = student1).
        show("social/profile.fxml");
        shot("41_social_profile");
        show("social/friends.fxml");
        shot("42_social_friends");
        show("social/messages.fxml");
        shot("43_social_messages");

        // 8. Parent portal.
        asParent();
        show("parent_dashboard.fxml");
        shot("44_parent_portal");

        // 9. Document exports - prove the PDF pipeline really generates
        //    official documents from the seeded data (report card,
        //    transcript, graduation certificate).
        pdfExports();

        // 10. Done.
        System.out.println("[harness] done - " + (failures == 0 ? "ALL CLEAN" : failures + " failures")
            + " - screenshots in " + outDir);
        Platform.exit();
        System.exit(failures == 0 ? 0 : 1);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static void fillLoginFields() {
        onFX(() -> {
            try {
                Scene sc = MainApp.primaryStage.getScene();
                Node email = sc.lookup("#emailField");
                Node pass  = sc.lookup("#passField");
                if (email instanceof TextField) ((TextField) email).setText("admin@klc.test");
                if (pass  instanceof TextField) ((TextField) pass).setText("Password123!");
            } catch (Exception e) { err(e); }
        });
    }

    /** Load an FXML exactly like MainApp.setRoot (css + theme); returns the controller. */
    private static Object show(final String fxml) {
        final Object[] ctrl = new Object[1];
        onFX(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(
                    MainApp.class.getResource("/fxml/" + fxml));
                Parent root = loader.load();
                Scene old = MainApp.primaryStage.getScene();
                Scene scene = (old != null)
                    ? new Scene(root, old.getWidth(), old.getHeight())
                    : new Scene(root, W, H);
                scene.getStylesheets().add(
                    MainApp.class.getResource("/css/klc-premium.css").toExternalForm());
                com.femzyk.klc.util.ThemeService.apply(scene);
                MainApp.primaryStage.setScene(scene);
                ctrl[0] = loader.getController();
            } catch (Exception e) {
                failures++;
                System.out.println("[harness] FAILED to load " + fxml + ": " + e);
                err(e);
            }
        });
        return ctrl[0];
    }

    private static void shot(final String name) {
        onFX(() -> {
            try {
                Scene sc = MainApp.primaryStage.getScene();
                if (sc == null || sc.getRoot() == null) {
                    failures++;
                    System.out.println("[harness] no scene for " + name);
                    return;
                }
                WritableImage img = sc.snapshot(null);
                int w = (int) img.getWidth(), h = (int) img.getHeight();
                int[] px = new int[w * h];
                img.getPixelReader().getPixels(0, 0, w, h,
                    PixelFormat.getIntArgbInstance(), px, 0, w);
                BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                bi.setRGB(0, 0, w, h, px, 0, w);
                File f = new File(outDir, name + ".png");
                javax.imageio.ImageIO.write(bi, "png", f);
                System.out.println("[harness] wrote " + f.getName() + " (" + w + "x" + h + ")");
            } catch (Exception e) {
                failures++;
                System.out.println("[harness] snapshot failed for " + name + ": " + e);
                err(e);
            }
        });
        sleep(500);   // let async data loaders paint
    }

    /** Make sure the theme equals {@code want}, applied to the live scene. */
    private static void theme(final boolean want) {
        onFX(() -> {
            try {
                if (com.femzyk.klc.util.ThemeService.isDark() != want) {
                    com.femzyk.klc.util.ThemeService.toggle();
                }
                Scene sc = MainApp.primaryStage.getScene();
                if (sc != null) com.femzyk.klc.util.ThemeService.apply(sc);
            } catch (Exception e) { err(e); }
        });
    }

    private static void onFX(Runnable r) {
        CountDownLatch l = new CountDownLatch(1);
        Platform.runLater(() -> {
            try { r.run(); } catch (Throwable e) { failures++; err(e); }
            finally { l.countDown(); }
        });
        try {
            if (!l.await(90, TimeUnit.SECONDS))
                System.out.println("[harness] FX task timed out");
        } catch (InterruptedException ignored) {}
    }

    private static void sleep(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private static void err(Throwable e) {
        log.println(e.toString());
        for (StackTraceElement el : e.getStackTrace()) log.println("  at " + el);
        Throwable c = e;
        while (c.getCause() != null) { c = c.getCause(); log.println("caused by " + c); }
        log.flush();
    }

    private static void fail(String m) { System.out.println("[harness] FATAL: " + m); }

    // ── sessions ───────────────────────────────────────────────────────────

    static String superAdminId, teacherId, student1Id;

    private static void asSuperAdmin() { session(superAdminId, "Dr. (Mrs.) F. Adeniyi", "SUPER_ADMIN", "admin@klc.test"); }
    private static void asStudent1()   { session(student1Id,  "Chidera Okonkwo",       "STUDENT",     "student1@klc.test"); }
    private static void asParent()     { session(idByEmail("parent1@klc.test"), "Mr. Emeka Okonkwo", "PARENT", "parent1@klc.test"); }

    private static void session(String id, String name, String role, String email) {
        AuthService.Session.userId = id;
        AuthService.Session.fullName = name;
        AuthService.Session.role = role;
        AuthService.Session.email = email;
        AuthService.Session.touch();
    }

    // ── demo dataset ───────────────────────────────────────────────────────

    private static void seed() throws Exception {
        String codeSA  = prop("code.super_admin");
        String codeAdm = prop("code.admin");
        String codeStu = prop("code.student");

        // 1. Real users through the app's own AuthService (bcrypt, profiles, PINs).
        superAdminId = reg("Dr. (Mrs.) F. Adeniyi", "admin@klc.test", "Password123!",
            "SUPER_ADMIN", codeSA, null, null, null, "Adeniyi");
        teacherId    = reg("Mrs. Adaeze Okafor", "teacher1@klc.test", "Teacher2026x",
            "TEACHER", codeAdm, null, null, null, "Okafor");

        String[][] students = {
            {"Chidera Okonkwo", "student1@klc.test", "KLC/2025/001", "JSS1", "A", "Okonkwo",   "Female"},
            {"Ibrahim Musa",    "student2@klc.test", "KLC/2025/002", "JSS1", "A", "Musa",      "Male"},
            {"Blessing Eze",    "student3@klc.test", "KLC/2025/003", "JSS1", "A", "Eze",       "Female"},
            {"Tunde Balogun",   "student4@klc.test", "KLC/2025/004", "JSS1", "A", "Balogun",   "Male"},
            {"Aisha Abdullahi", "student5@klc.test", "KLC/2025/005", "JSS1", "B", "Abdullahi", "Female"},
            {"Emeka Nwosu",     "student6@klc.test", "KLC/2025/006", "SS2",  "B", "Nwosu",     "Male"},
            {"Fatima Sule",     "student7@klc.test", "KLC/2025/007", "SS3",  "A", "Sule",      "Female"},
        };
        for (String[] s : students) {
            reg(s[0], s[1], "Student2026", "STUDENT", codeStu, s[2], s[3], s[4], s[5]);
            sql("UPDATE student_profiles SET gender='" + s[6] + "', other_names='" +
                s[0].split(" ")[0] + "' WHERE admission_no='" + s[2] + "'");
        }
        student1Id = idByEmail("student1@klc.test");

        reg("Mr. Emeka Okonkwo", "parent1@klc.test", "Parent2026", "PARENT", codeStu,
            "KLC/2025/001", null, null, "Okonkwo");

        // 2. Classes + teacher subject assignment.
        sql("INSERT INTO school_classes (id, class_level, arm, session, is_active) VALUES " +
            "('" + uuid("cls1") + "','JSS1','A','2025/2026',TRUE)," +
            "('" + uuid("cls2") + "','JSS1','B','2025/2026',TRUE)," +
            "('" + uuid("cls3") + "','SS2','B','2025/2026',TRUE)," +
            "('" + uuid("cls4") + "','SS3','A','2025/2026',TRUE)");

        String mathId = scalar("SELECT id FROM subjects WHERE subject_code='MAT-JSS1'");
        String engId  = scalar("SELECT id FROM subjects WHERE subject_code='ENG-JSS1'");
        sql("INSERT INTO teacher_subjects (id, teacher_id, subject_id, class_level) VALUES " +
            "('" + uuid("ts1") + "','" + teacherId + "','" + mathId + "','JSS1')," +
            "('" + uuid("ts2") + "','" + teacherId + "','" + engId + "','JSS1')");

        // 3. Question bank (Mathematics JSS1, topic-tagged, approved).
        String[][] qs = {
            {"Fractions",  "What is 3/4 + 1/8?",                        "7/8",  "4/12", "3/8",  "1/2",  "5/8",  "A"},
            {"Algebra",    "Solve for x:   2x + 6 = 18",                "6",    "9",    "12",   "8",    "4",    "A"},
            {"Geometry",   "The sum of angles in a triangle is:",       "180 degrees", "360 degrees", "90 degrees", "270 degrees", "45 degrees", "A"},
            {"Numbers",    "Express 0.25 as a fraction in its lowest term", "1/4", "2/5", "1/2", "25/100", "5/20", "A"},
            {"Statistics", "The mean of 4, 8, 6 and 2 is:",             "5",    "6",    "4",    "20",   "10",   "A"},
        };
        List<String> qids = new ArrayList<>();
        for (int k = 0; k < qs.length; k++) {
            String qid = uuid("q" + k);
            qids.add(qid);
            sql("INSERT INTO questions (id, subject_id, class_level, term, topic, difficulty, " +
                "question_text, question_type, marks, is_approved, created_by) VALUES ('" + qid +
                "','" + mathId + "','JSS1','1st','" + qs[k][0] + "','MEDIUM','" + qs[k][1] +
                "','MCQ',5,TRUE,'" + teacherId + "')");
            String[] labels = {"A","B","C","D","E"};
            for (int o = 0; o < 5; o++) {
                sql("INSERT INTO question_options (id, question_id, option_label, option_text, is_correct) VALUES ('" +
                    uuid("q" + k + "o" + o) + "','" + qid + "','" + labels[o] + "','" +
                    qs[k][2 + o] + "'," + qs[k][7].equals(labels[o]) + ")");
            }
        }

        // 4. Live official exams (window includes 'now' so they are takeable).
        String exam1 = uuid("exam1"), exam2 = uuid("exam2");
        sql("INSERT INTO exams (id, subject_id, class_level, arm, term, session, title, instructions, " +
            "duration_minutes, total_marks, pass_mark, start_at, end_at, is_active, created_by) VALUES (" +
            "'" + exam1 + "','" + mathId + "','JSS1','A','1st','2025/2026'," +
            "'First Term Mathematics Examination (2025/2026)'," +
            "'Answer ALL questions. Examination malpractice attracts 3 strikes and automatic submission.'," +
            "45, 100, 40, CURRENT_TIMESTAMP - INTERVAL '1' HOUR, CURRENT_TIMESTAMP + INTERVAL '3' HOUR, TRUE,'" +
            superAdminId + "')");
        sql("INSERT INTO exams (id, subject_id, class_level, arm, term, session, title, instructions, " +
            "duration_minutes, total_marks, pass_mark, start_at, end_at, is_active, created_by) VALUES (" +
            "'" + exam2 + "','" + engId + "','JSS1','A','1st','2025/2026'," +
            "'First Term English Language Examination (2025/2026)'," +
            "'Answer ALL questions.'," +
            "45, 100, 40, CURRENT_TIMESTAMP - INTERVAL '2' HOUR, CURRENT_TIMESTAMP + INTERVAL '4' HOUR, TRUE,'" +
            superAdminId + "')");
        for (int i = 0; i < qids.size(); i++) {
            sql("INSERT INTO exam_questions (exam_id, question_id, question_order) VALUES ('" +
                exam1 + "','" + qids.get(i) + "'," + (i + 1) + ")");
            sql("INSERT INTO exam_questions (exam_id, question_id, question_order) VALUES ('" +
                exam2 + "','" + qids.get(i) + "'," + (i + 1) + ")");
        }

        // 5. Completed attempts + published results (realistic spread).
        String[][] attempts = {
            {"student1@klc.test", exam1, "18", "90", "A", "1"},
            {"student2@klc.test", exam1, "15", "75", "A", "2"},
            {"student3@klc.test", exam1, "12", "60", "B", "3"},
            {"student4@klc.test", exam1, "9",  "45", "D", "4"},
            {"student5@klc.test", exam1, "7",  "35", "F", "5"},
            {"student1@klc.test", exam2, "16", "80", "A", "1"},
            {"student2@klc.test", exam2, "13", "65", "B", "2"},
            {"student3@klc.test", exam2, "8",  "40", "D", "3"},
        };
        for (int a = 0; a < attempts.length; a++) {
            String sid = idByEmail(attempts[a][0]);
            String adm = scalar("SELECT admission_no FROM student_profiles WHERE user_id='" + sid + "'");
            String aid = uuid("att" + a);
            int correct = Integer.parseInt(attempts[a][2]);
            sql("INSERT INTO exam_attempts (id, exam_id, student_id, admission_no, variant, started_at, " +
                "submitted_at, time_remaining, strike_count, status, synced) VALUES ('" + aid +
                "','" + attempts[a][1] + "','" + sid + "','" + adm + "','A'," +
                "CURRENT_TIMESTAMP - INTERVAL '2' HOUR, CURRENT_TIMESTAMP - INTERVAL '1' HOUR, 0, 0, " +
                "'COMPLETED', TRUE)");
            sql("INSERT INTO results (id, attempt_id, student_id, exam_id, score, total_questions, " +
                "correct_answers, percentage, grade, position, published) VALUES ('" + uuid("res" + a) +
                "','" + aid + "','" + sid + "','" + attempts[a][1] + "'," + (correct * 5) + ",20," +
                correct + "," + attempts[a][3] + ",'" + attempts[a][4] + "'," + attempts[a][5] + ", TRUE)");
        }

        // 6. CA scores for the JSS1 A Maths class.
        String[][] cas = {
            {"student1@klc.test", "24", "23", "A", "Excellent"},
            {"student2@klc.test", "20", "19", "A", "Very Good"},
            {"student3@klc.test", "16", "15", "B", "Good"},
            {"student4@klc.test", "12", "11", "D", "Fair"},
            {"student5@klc.test", "8",  "9",  "F", "Needs help"},
        };
        for (int c = 0; c < cas.length; c++) {
            String sid = idByEmail(cas[c][0]);
            double ca1 = Double.parseDouble(cas[c][1]), ca2 = Double.parseDouble(cas[c][2]);
            sql("INSERT INTO ca_scores (id, student_id, subject_id, class_level, term, session, " +
                "ca1_score, ca2_score, exam_score, total_score, grade, remark, position) VALUES ('" +
                uuid("ca" + c) + "','" + sid + "','" + mathId + "','JSS1','1st','2025/2026'," +
                ca1 + "," + ca2 + "," + ((ca1 + ca2) / 2) + "," + (ca1 + ca2) + ",'" + cas[c][3] +
                "','" + cas[c][4] + "'," + (c + 1) + ")");
        }

        // 7. Announcements, study material, appeal.
        sql("INSERT INTO announcements (id, title, body, target_role, status, created_by) VALUES ('" +
            uuid("ann1") + "','Mid-Term Break','Mid-term break holds on Friday. Classes resume on Monday.','ALL','APPROVED','" +
            superAdminId + "')");
        sql("INSERT INTO announcements (id, title, body, target_role, status, created_by) VALUES ('" +
            uuid("ann2") + "','Mathematics Revision Class','Extra Maths revision holds Saturday 9am in Lab 1.','STUDENT','APPROVED','" +
            teacherId + "')");
        sql("INSERT INTO study_materials (id, subject_id, class_level, title, description, file_url, uploaded_by) VALUES ('" +
            uuid("mat1") + "','" + mathId + "','JSS1','Fractions Workbook','Practice drills on fractions for the first term.','klc_assets/materials/fractions_workbook.pdf','" +
            teacherId + "')");
        sql("INSERT INTO result_appeals (id, result_id, student_id, subject_code, reason, status) VALUES ('" +
            uuid("app1") + "','" + uuid("res4") + "','" + idByEmail("student5@klc.test") +
            "','MAT-JSS1','Question 4 options were unclear when the network dropped.','OPEN')");

        System.out.println("[harness] seed complete");
    }

    /** Register via the app's own AuthService and return the new user id. */
    private static String reg(String fullName, String email, String password, String role,
                              String regCode, String admissionNo, String classLevel,
                              String arm, String surname) throws Exception {
        String existing = idByEmail(email);
        if (existing != null) {
            System.out.println("[harness] " + email + " already exists (seeded) - reusing");
            return existing;
        }
        String r = AuthService.register(fullName, email, password, role, regCode,
            admissionNo, classLevel, arm, surname, null, "First pet?", "Cat");
        if (r != null && r.startsWith("OK:")) {
            System.out.println("[harness] registered " + role + " " + email);
            return r.substring(3);
        }
        failures++;
        System.out.println("[harness] register FAILED for " + email + ": " + r);
        return idByEmail(email);
    }

    /** Generate the official PDF documents through the app's own services. */
    private static void pdfExports() {
        try {
            String ss3 = idByEmail("student7@klc.test");   // Fatima Sule (SS3)
            String jss1 = student1Id;                      // Chidera Okonkwo

            String rc = com.femzyk.klc.util.ReportCardService
                .generateReportCard(jss1, "1st", "2025/2026");
            verdict("report card", rc != null, rc);

            String tr = com.femzyk.klc.util.ReportCardService
                .generateTranscript(jss1);
            verdict("transcript", tr != null, tr);

            String cert = com.femzyk.klc.util.GraduationCertificatePdf
                .generate(ss3, outDir + "/sample_graduation_certificate.pdf");
            verdict("graduation certificate", cert != null, cert);
        } catch (Throwable t) {
            failures++;
            System.out.println("[harness] PDF export step FAILED: " + t);
            err(t);
        }
    }

    private static void verdict(String what, boolean ok, String path) {
        if (ok) {
            long len = path != null ? new File(path).length() : -1;
            System.out.println("[harness] PDF " + what + " generated: " + path
                + " (" + len + " bytes)");
        } else {
            failures++;
            System.out.println("[harness] PDF " + what + " FAILED (null path)");
        }
    }

    /** Load exam.fxml and start the seeded Mathematics exam via the real controller entry point. */
    private static void startSeededExam() {
        Object c = show("exam.fxml");
        final String eid = scalar(
            "SELECT id FROM exams WHERE title LIKE 'First Term Mathematics%'");
        if (c == null || eid == null) { failures++; fail("exam screen skipped"); return; }
        final Object ctrl = c;
        onFX(() -> {
            try {
                ctrl.getClass().getMethod("startExam", String.class, String.class)
                    .invoke(ctrl, eid, "A");
            } catch (Exception e) { failures++; err(e); }
        });
        sleep(3000);  // timer + question render (+ fullscreen transition)
    }

    // ── tiny JDBC helpers ──────────────────────────────────────────────────

    private static void sql(String s) throws Exception {
        try (Connection c = DatabaseManager.getConnection();
             Statement st = c.createStatement()) {
            st.execute(s);
        }
    }

    private static String scalar(String s) {
        try (Connection c = DatabaseManager.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(s)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) { failures++; err(e); return null; }
    }

    private static String idByEmail(String email) {
        return scalar("SELECT id FROM users WHERE LOWER(email)='" + email.toLowerCase() + "'");
    }

    private static String prop(String k) {
        return com.femzyk.klc.util.ConfigService.get(k, "");
    }

    /** Deterministic readable UUID-style ids for seeded rows. */
    private static String uuid(String k) {
        return String.format("klc00000-0000-0000-0000-%012d",
            Math.abs(k.hashCode() * 2654435761L) % 1_000_000_000_000L);
    }
}

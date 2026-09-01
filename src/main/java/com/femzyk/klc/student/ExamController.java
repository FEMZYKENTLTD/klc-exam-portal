package com.femzyk.klc.student;

import com.femzyk.klc.MainApp;
import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.db.DatabaseManager;
import com.femzyk.klc.proctoring.FocusLossDetector;
import com.femzyk.klc.proctoring.WebcamProctorService;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.sql.*;
import java.util.*;

public class ExamController {

    // ─── FXML Fields ──────────────────────────────────────────────────────────
    @FXML private Label timerLabel, questionLabel, strikeLabel, examTitleLabel;
    @FXML private RadioButton optA, optB, optC, optD, optE;
    @FXML private ToggleGroup optionsGroup;
    @FXML private FlowPane navPane;
    @FXML private Button prevBtn, nextBtn, flagBtn, submitBtn, calcBtn, formulaBtn;
    @FXML private Label progressLabel;
    @FXML private ImageView questionImageView, webcamThumb;
    @FXML private VBox examRoot;

    // ─── State ────────────────────────────────────────────────────────────────
    private String attemptId, examId;
    private List<Question> questions   = new ArrayList<>();
    private int index                  = 0;
    private Map<String, String> answers          = new HashMap<>();
    private Map<String, String> correctAnswerMap = new HashMap<>();
    private Set<String> flagged                  = new HashSet<>();
    private int timeLeft;
    private Timeline timer;
    private Timeline autosaveTimer;
    private double fontScale   = 1.0;
    private boolean highContrast  = false;
    private boolean dyslexicFont  = false;
    private boolean isPractice    = false;
    private String formulaSheetText =
        "Area = L x W\nVolume = L x W x H\na^2 + b^2 = c^2\n" +
        "Quadratic: x = (-b +/- sqrt(b^2-4ac))/2a\nSpeed = Distance / Time";
    private double negativeMarking = 0.0;
    private String examVariant     = "A";
    private WebcamProctorService webcamProctor;

    // Stores result info for display after submit
    private String lastResultSummary = "";

    // ─── Inner Model ──────────────────────────────────────────────────────────
    static class Question {
        String id, text, imageUrl, type, topic;
        Map<String, String> opts = new LinkedHashMap<>();
    }

    // =========================================================================
    //  START EXAM
    // =========================================================================
    public void startExam(String examId, String variant) {
        this.examId      = examId;
        this.examVariant = (variant == null) ? "A" : variant;
        String admissionNo = "";

        // FIX: Set exam in progress flag - blocks app close button
        MainApp.examInProgress = true;

        try (Connection c = DatabaseManager.getConnection()) {
            admissionNo = checkFeeAndSchedule(c);
            if (admissionNo == null) {
                MainApp.examInProgress = false;
                return;
            }
            attemptId = createAttempt(c);
            loadExamMeta(c);
            loadQuestions(c);

        } catch (Exception e) {
            e.printStackTrace();
            MainApp.examInProgress = false;
            alert("Error starting exam: " + e.getMessage());
            return;
        }

        buildNav();
        showQuestion(0);
        startTimer();
        setupKeyboardShortcuts();

        // KLC v1.0 FIX: README promises "answers save locally every 30
        // seconds" - force-save the currently selected answer on a 30s
        // cycle even if the student never navigates, and run the cloud
        // auto-sync loop for the duration of the exam (null label = silent).
        startAutosave();
        com.femzyk.klc.util.SyncService.startAutoSync(null);

        Stage st = (Stage) timerLabel.getScene().getWindow();
        st.setFullScreen(true);

        // Anti-Malpractice: Only enable 3-strike proctoring for official (non-practice) exams
        if (!isPractice) {
            new FocusLossDetector(
                st,
                strikes -> {
                    strikeLabel.setText("Malpractice Strikes: " + strikes + "/3");
                    if (strikes == 1)
                        alert("WARNING: Do not minimize or switch apps! Strike " + strikes + "/3");
                    if (strikes == 2)
                        alert("FINAL WARNING! Next offense = Auto-submit");
                },
                () -> submitExam(true)
            );
        } else {
            if (strikeLabel != null) {
                strikeLabel.setText("Practice Mode | Anti-Malpractice Disabled");
            }
        }

        // Webcam (Optional in Practice Mode)
        try {
            final String finalAdmNo = admissionNo;
            webcamProctor = new WebcamProctorService(finalAdmNo, img -> {
                if (webcamThumb != null) webcamThumb.setImage(img);
            });
            if (webcamProctor.isEnabled()) {
                webcamProctor.start();
                if (strikeLabel != null && !isPractice)
                    strikeLabel.setText(strikeLabel.getText() + " | Webcam ON");
            }
        } catch (Exception ignored) {}
    }

    // =========================================================================
    //  DB HELPERS
    // =========================================================================
    private String checkFeeAndSchedule(Connection c) throws SQLException {
        String sql = """
            SELECT e.fee_gate, sp.fee_status, e.start_at, e.end_at,
                   e.negative_marking, sp.admission_no, COALESCE(e.is_practice, FALSE)
            FROM exams e
            LEFT JOIN student_profiles sp ON sp.user_id = ?
            WHERE e.id = ?
            """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            AuthService.setUuid(ps, 1, AuthService.Session.userId, c);
            AuthService.setUuid(ps, 2, examId, c);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) {
                alert("Exam not found in database.");
                return null;
            }
            boolean feeGate    = rs.getBoolean(1);
            String feeStatus   = rs.getString(2);
            Timestamp start    = rs.getTimestamp(3);
            Timestamp end      = rs.getTimestamp(4);
            negativeMarking    = rs.getDouble(5);
            String admNo       = rs.getString(6);
            boolean prFlag     = rs.getBoolean(7);

            // Skip fee and schedule gates if this is a practice exam
            if (!prFlag) {
                if (feeGate && "UNPAID".equalsIgnoreCase(feeStatus)) {
                    alert("Fee Clearance Required.\nYour fee status is UNPAID.\nContact the Bursar.");
                    return null;
                }
                Timestamp now = new Timestamp(System.currentTimeMillis());
                if (start != null && now.before(start)) {
                    alert("Exam has not started yet.\nScheduled start: " + start);
                    return null;
                }
                if (end != null && now.after(end)) {
                    alert("Exam window has closed.\nEnd time: " + end);
                    return null;
                }
            }
            return admNo == null ? "student" : admNo;
        }
    }

    private String createAttempt(Connection c) throws SQLException {
        String newId = UUID.randomUUID().toString();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO exam_attempts(id, exam_id, student_id, variant) " +
                "VALUES(?,?,?,?)")) {
            AuthService.setUuid(ps, 1, newId, c);
            AuthService.setUuid(ps, 2, examId, c);
            AuthService.setUuid(ps, 3, AuthService.Session.userId, c);
            ps.setString(4, examVariant);
            ps.executeUpdate();
        }
        return newId;
    }

    private void loadExamMeta(Connection c) throws SQLException {
        String sql = """
            SELECT e.duration_minutes,
                   s.subject_code || ' - ' || e.class_level AS title,
                   COALESCE(fs.content,'') AS formula,
                   e.negative_marking,
                   COALESCE(e.is_practice, FALSE)
            FROM exams e
            JOIN subjects s ON s.id = e.subject_id
            LEFT JOIN formula_sheets fs
                   ON fs.subject_id = s.id AND fs.is_active = TRUE
            WHERE e.id = ?
            """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            AuthService.setUuid(ps, 1, examId, c);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                timeLeft = rs.getInt(1) * 60;
                String titleText = rs.getString(2) + "  [Variant " + examVariant + "]";
                String f = rs.getString(3);
                if (f != null && !f.isBlank()) formulaSheetText = f;
                negativeMarking = rs.getDouble(4);
                isPractice = rs.getBoolean(5);
                if (isPractice) {
                    titleText += "  [PRACTICE MODE - NOT RECORDED]";
                }
                if (examTitleLabel != null)
                    examTitleLabel.setText(titleText);
            }
        }
    }

    private void loadQuestions(Connection c) throws SQLException {
        String sql = """
            SELECT q.id, q.question_text, q.question_image_url, q.question_type,
                   o.option_label, o.option_text, o.is_correct, q.topic
            FROM exam_questions eq
            JOIN questions q ON q.id = eq.question_id
            LEFT JOIN question_options o ON o.question_id = q.id
            WHERE eq.exam_id = ?
            ORDER BY eq.question_order, o.option_label
            """;

        try (PreparedStatement ps = c.prepareStatement(sql)) {
            AuthService.setUuid(ps, 1, examId, c);
            ResultSet rs = ps.executeQuery();

            Map<String, Question> map           = new LinkedHashMap<>();
            Map<String, String> originalCorrect = new HashMap<>();
            Map<String, Map<String, String>> originalOptions = new HashMap<>();

            // FIX: Only ONE rs.next() call per loop iteration
            while (rs.next()) {
                String qid = rs.getString(1);
                if (qid == null) continue;

                final String qText     = rs.getString(2);
                final String qImageUrl = rs.getString(3);
                final String qType     = rs.getString(4) == null
                                       ? "MCQ" : rs.getString(4);
                final String qTopic    = rs.getString(8);

                Question qq = map.computeIfAbsent(qid, k -> {
                    Question n  = new Question();
                    n.id        = k;
                    n.text      = qText;
                    n.imageUrl  = qImageUrl;
                    n.type      = qType;
                    n.topic     = qTopic;
                    return n;
                });

                String optionLabel = rs.getString(5);
                if (optionLabel != null) {
                    String optionText = rs.getString(6);
                    boolean isCorrect = rs.getBoolean(7);
                    qq.opts.put(optionLabel, optionText);
                    if (isCorrect) originalCorrect.put(qid, optionLabel);
                    originalOptions
                        .computeIfAbsent(qid, k -> new LinkedHashMap<>())
                        .put(optionLabel, optionText);
                }
            }

            questions.addAll(map.values());
            correctAnswerMap.putAll(originalCorrect);

            // Variant shuffling
            if (!"A".equalsIgnoreCase(examVariant)) {
                long seed = (long)(examId + AuthService.Session.userId
                                         + examVariant).hashCode();
                Random rnd = new Random(seed);
                Collections.shuffle(questions, rnd);

                for (Question q : questions) {
                    List<Map.Entry<String,String>> entries =
                        new ArrayList<>(q.opts.entrySet());
                    Collections.shuffle(entries,
                        new Random((q.id + examVariant).hashCode()));

                    Map<String,String> newOpts = new LinkedHashMap<>();
                    String[] labels        = {"A","B","C","D","E"};
                    String origCorrectLbl  = originalCorrect.get(q.id);
                    String origCorrectTxt  = originalOptions
                        .getOrDefault(q.id, Collections.emptyMap())
                        .get(origCorrectLbl);
                    String newCorrectLabel = "A";
                    int i = 0;
                    for (Map.Entry<String,String> e : entries) {
                        if (i >= labels.length) break;
                        String nl = labels[i++];
                        newOpts.put(nl, e.getValue());
                        if (e.getValue() != null
                                && e.getValue().equals(origCorrectTxt))
                            newCorrectLabel = nl;
                    }
                    q.opts = newOpts;
                    correctAnswerMap.put(q.id, newCorrectLabel);
                }
            }
        }
    }

    // =========================================================================
    //  KEYBOARD SHORTCUTS
    // =========================================================================
    private void setupKeyboardShortcuts() {
        Scene sc = timerLabel.getScene();
        if (sc == null) {
            timerLabel.sceneProperty().addListener((o, ov, nv) -> {
                if (nv != null) setupKeyboardShortcuts();
            });
            return;
        }

        // KLC v1.0 FIX: README claims "copy-paste is blocked" during exams.
        // Consume Ctrl+C / X / V / A and right-click context menus inside
        // the exam scene so question text cannot be copied out or answers
        // pasted in.
        sc.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.isControlDown() && (
                    e.getCode() == KeyCode.C || e.getCode() == KeyCode.V ||
                    e.getCode() == KeyCode.X || e.getCode() == KeyCode.A)) {
                e.consume();
            }
        });
        sc.addEventFilter(javafx.scene.input.ContextMenuEvent.ANY,
            e -> e.consume());

        sc.setOnKeyPressed(e -> {
            if      (e.getCode() == KeyCode.N
                  || e.getCode() == KeyCode.RIGHT) next();
            else if (e.getCode() == KeyCode.P
                  || e.getCode() == KeyCode.LEFT)  prev();
            else if (e.getCode() == KeyCode.F)      toggleFlag();
            else if (e.getCode() == KeyCode.DIGIT1) selectOpt("A");
            else if (e.getCode() == KeyCode.DIGIT2) selectOpt("B");
            else if (e.getCode() == KeyCode.DIGIT3) selectOpt("C");
            else if (e.getCode() == KeyCode.DIGIT4) selectOpt("D");
            else if (e.getCode() == KeyCode.DIGIT5) selectOpt("E");
        });
    }

    private void selectOpt(String label) {
        RadioButton[] arr  = {optA, optB, optC, optD, optE};
        String[]      labs = {"A","B","C","D","E"};
        for (int i = 0; i < labs.length; i++) {
            if (labs[i].equals(label) && arr[i].isVisible()) {
                optionsGroup.selectToggle(arr[i]);
                return;
            }
        }
    }

    // =========================================================================
    //  NAVIGATION
    // =========================================================================
    private void buildNav() {
        navPane.getChildren().clear();
        for (int i = 0; i < questions.size(); i++) {
            Button b = new Button(String.valueOf(i + 1));
            b.getStyleClass().add("question-nav-btn");
            int idx = i;
            b.setOnAction(e -> { saveCurrent(); showQuestion(idx); });
            navPane.getChildren().add(b);
        }
        updateNav();
    }

    private void updateNav() {
        for (int i = 0; i < navPane.getChildren().size(); i++) {
            Button b   = (Button) navPane.getChildren().get(i);
            String qid = questions.get(i).id;
            b.getStyleClass().removeAll("answered", "flagged");
            if (answers.containsKey(qid)) b.getStyleClass().add("answered");
            if (flagged.contains(qid))    b.getStyleClass().add("flagged");
        }
        progressLabel.setText(
            "Answered: " + answers.size() + " / " + questions.size());
    }

    private void showQuestion(int i) {
        saveCurrent();
        index = i;
        Question q = questions.get(index);
        renderQuestionText(i + 1, q.text);
        applyFontStyle();

        if (questionImageView != null) {
            questionImageView.setVisible(false);
            questionImageView.setManaged(false);
            if (q.imageUrl != null && !q.imageUrl.isBlank()) {
                try {
                    Image img;
                    if (q.imageUrl.startsWith("http"))
                        img = new Image(q.imageUrl, 560, 280, true, true, true);
                    else
                        img = new Image(
                            new java.io.File(q.imageUrl).toURI().toString(),
                            560, 280, true, true);
                    if (!img.isError()) {
                        questionImageView.setImage(img);
                        questionImageView.setVisible(true);
                        questionImageView.setManaged(true);
                    }
                } catch (Exception ignored) {}
            }
        }

        boolean isTF = "TRUE_FALSE".equalsIgnoreCase(q.type);
        optA.setText("A. " + q.opts.getOrDefault("A", isTF ? "True"  : ""));
        optA.setVisible(q.opts.containsKey("A") || isTF);
        optA.setManaged(q.opts.containsKey("A") || isTF);
        optB.setText("B. " + q.opts.getOrDefault("B", isTF ? "False" : ""));
        optB.setVisible(q.opts.containsKey("B") || isTF);
        optB.setManaged(q.opts.containsKey("B") || isTF);
        optC.setText("C. " + q.opts.getOrDefault("C", ""));
        optC.setVisible(q.opts.containsKey("C"));
        optC.setManaged(q.opts.containsKey("C"));
        optD.setText("D. " + q.opts.getOrDefault("D", ""));
        optD.setVisible(q.opts.containsKey("D"));
        optD.setManaged(q.opts.containsKey("D"));
        optE.setText("E. " + q.opts.getOrDefault("E", ""));
        optE.setVisible(q.opts.containsKey("E"));
        optE.setManaged(q.opts.containsKey("E"));

        optionsGroup.selectToggle(null);
        String sel = answers.get(q.id);
        if ("A".equals(sel)) optionsGroup.selectToggle(optA);
        if ("B".equals(sel)) optionsGroup.selectToggle(optB);
        if ("C".equals(sel)) optionsGroup.selectToggle(optC);
        if ("D".equals(sel)) optionsGroup.selectToggle(optD);
        if ("E".equals(sel)) optionsGroup.selectToggle(optE);

        flagBtn.setText(flagged.contains(q.id) ? "Unflag" : "Flag");
    }

    private void applyFontStyle() {
        String family = dyslexicFont
            ? "'OpenDyslexic','Comic Sans MS',Arial,sans-serif"
            : "'Segoe UI', Arial, sans-serif";
        questionLabel.setStyle(
            "-fx-font-size: " + (16 * fontScale) + "px;" +
            " -fx-font-family: " + family + ";");
    }

    // =========================================================================
    //  QUESTION TEXT RENDERING (KLC v1.0 - LaTeX / WAEC formula support)
    //  Plain label for normal text. Questions containing LaTeX markers
    //  are ALSO rendered through an embedded WebView + MathJax.
    //  Offline: the WebView fails silently and the plain-text label
    //  remains - an exam never blocks on rendering.
    // =========================================================================
    private javafx.scene.web.WebView latexView;

    private static boolean looksLikeLatex(String t) {
        if (t == null) return false;
        return t.contains("$") || t.contains("\\frac") || t.contains("\\sqrt")
            || t.contains("\\int") || t.contains("\\sum") || t.contains("\\pi")
            || t.contains("\\alpha") || t.contains("_{") || t.contains("^");
    }

    private static String escapeHtml(String s) {
        return s == null ? "" : s
            .replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;");
    }

    private void renderQuestionText(int number, String text) {
        // Plain label is ALWAYS set first (offline-safe + accessibility).
        questionLabel.setText(number + ". " + text);
        questionLabel.setVisible(true);
        questionLabel.setManaged(true);
        if (latexView != null) {
            latexView.setVisible(false);
            latexView.setManaged(false);
        }
        if (!looksLikeLatex(text)) return;

        try {
            if (latexView == null) {
                if (!(questionLabel.getParent()
                        instanceof javafx.scene.layout.Pane parent)) return;
                latexView = new javafx.scene.web.WebView();
                latexView.setPrefHeight(190);
                parent.getChildren().add(latexView);
            }
            String html =
                "<html><head><meta charset='utf-8'/>"
                + "<script>window.MathJax = {tex: {inlineMath: "
                + "[['$','$'],['\\\\(','\\\\)']], displayMath: "
                + "[['$$','$$']]}};</script>"
                + "<script src='https://cdn.jsdelivr.net/npm/mathjax@3/"
                + "es5/tex-mjx.js'></script>"
                + "</head><body style='font-family:Segoe UI,Arial;"
                + "font-size:15px;margin:6px;'>"
                + number + ".&nbsp;" + escapeHtml(text)
                + "</body></html>";
            latexView.getEngine().loadContent(html);
            latexView.setVisible(true);
            latexView.setManaged(true);
            latexView.getEngine().getLoadWorker().stateProperty()
                .addListener((o, ov, nv) -> {
                    if (nv == javafx.concurrent.Worker.State.SUCCEEDED) {
                        // Rendered fine - hide the duplicate plain label
                        questionLabel.setVisible(false);
                        questionLabel.setManaged(false);
                    } else if (nv
                            == javafx.concurrent.Worker.State.FAILED) {
                        // Offline (MathJax CDN unreachable) - keep label
                        latexView.setVisible(false);
                        latexView.setManaged(false);
                        questionLabel.setVisible(true);
                        questionLabel.setManaged(true);
                    }
                });
        } catch (Exception e) {
            // javafx-web unavailable or renderer error: plain text stands.
            System.out.println("[Exam] LaTeX render skipped: "
                + e.getMessage());
        }
    }

    // =========================================================================
    //  TOPIC-BY-TOPIC BREAKDOWN (KLC v1.0 - shown on the result dialog)
    // =========================================================================
    private String topicBreakdownText() {
        Map<String, int[]> byTopic = new LinkedHashMap<>();
        for (Question q : questions) {
            String t = (q.topic == null || q.topic.isBlank())
                ? "General" : q.topic;
            int[] agg = byTopic.computeIfAbsent(t, k -> new int[2]);
            agg[1]++;
            String sel = answers.get(q.id);
            if (sel != null && sel.equals(correctAnswerMap.get(q.id)))
                agg[0]++;
        }
        if (byTopic.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("TOPIC BREAKDOWN:\n");
        byTopic.forEach((k, v) -> sb.append(String.format(
            "  %-28s %d/%d correct%n", k, v[0], v[1])));
        return sb.toString();
    }

    @FXML private void increaseFont() {
        fontScale = Math.min(2.0, fontScale + 0.15);
        applyFontStyle();
    }
    @FXML private void decreaseFont() {
        fontScale = Math.max(0.85, fontScale - 0.15);
        applyFontStyle();
    }
    @FXML private void toggleContrast() {
        highContrast = !highContrast;
        if (examRoot != null)
            examRoot.setStyle(highContrast ? "-fx-background-color:#000;" : "");
        applyFontStyle();
    }
    @FXML private void toggleDyslexic() {
        dyslexicFont = !dyslexicFont;
        applyFontStyle();
    }

    // =========================================================================
    //  SAVE CURRENT
    // =========================================================================
    private void saveCurrent() {
        if (questions.isEmpty()) return;
        Question    q   = questions.get(index);
        RadioButton sel = (RadioButton) optionsGroup.getSelectedToggle();
        if (sel == null) return;

        String ans = sel == optA ? "A"
                   : sel == optB ? "B"
                   : sel == optC ? "C"
                   : sel == optD ? "D" : "E";
        answers.put(q.id, ans);

        try (Connection c = DatabaseManager.getConnection()) {
            try (PreparedStatement del = c.prepareStatement(
                    "DELETE FROM attempt_answers " +
                    "WHERE attempt_id=? AND question_id=?")) {
                AuthService.setUuid(del, 1, attemptId, c);
                AuthService.setUuid(del, 2, q.id, c);
                del.executeUpdate();
            }
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO attempt_answers" +
                    "(id, attempt_id, question_id, selected_option) " +
                    "VALUES(?,?,?,?)")) {
                AuthService.setUuid(ins, 1, UUID.randomUUID().toString(), c);
                AuthService.setUuid(ins, 2, attemptId, c);
                AuthService.setUuid(ins, 3, q.id, c);
                ins.setString(4, ans);
                ins.executeUpdate();
            }
        } catch (Exception ignored) {
            com.femzyk.klc.util.SyncService.queue(
                "attempt_answers",
                attemptId + "_" + q.id,
                "INSERT",
                Map.of("attempt_id", attemptId,
                       "question_id", q.id,
                       "selected_option", ans));
        }
        updateNav();
    }

    // =========================================================================
    //  NAVIGATION BUTTONS
    // =========================================================================
    @FXML private void next() {
        if (index < questions.size() - 1) showQuestion(index + 1);
    }
    @FXML private void prev() {
        if (index > 0) showQuestion(index - 1);
    }
    @FXML private void toggleFlag() {
        String qid = questions.get(index).id;
        if (!flagged.remove(qid)) flagged.add(qid);
        updateNav();
        flagBtn.setText(flagged.contains(qid) ? "Unflag" : "Flag");
    }

    // =========================================================================
    //  CALCULATOR
    // =========================================================================
    @FXML private void openCalculator() {
        Stage calc = new Stage();
        calc.initModality(Modality.NONE);
        calc.initOwner(timerLabel.getScene().getWindow());
        calc.setTitle("CBT Calculator");

        TextField display = new TextField("0");
        display.setEditable(false);
        display.setStyle("-fx-font-size:18px;");

        StringBuilder expr = new StringBuilder();
        VBox root = new VBox(6, display);
        root.setPadding(new Insets(10));

        String[][] buttons = {
            {"7","8","9","/"},
            {"4","5","6","*"},
            {"1","2","3","-"},
            {"0",".","C","+"},
            {"=","","",""}
        };

        for (String[] row : buttons) {
            javafx.scene.layout.HBox hb = new javafx.scene.layout.HBox(5);
            for (String b : row) {
                if (b.isEmpty()) continue;
                Button btn = new Button(b);
                btn.setPrefWidth(55);
                btn.setOnAction(e -> {
                    String t = btn.getText();
                    if ("C".equals(t)) {
                        expr.setLength(0);
                        display.setText("0");
                    } else if ("=".equals(t)) {
                        try {
                            double r = evalSimple(expr.toString());
                            display.setText(String.valueOf(r));
                            expr.setLength(0);
                            expr.append(r);
                        } catch (Exception ex) {
                            display.setText("Err");
                            expr.setLength(0);
                        }
                    } else {
                        expr.append(t);
                        display.setText(expr.toString());
                    }
                });
                hb.getChildren().add(btn);
            }
            root.getChildren().add(hb);
        }

        calc.setScene(new Scene(root));
        calc.setAlwaysOnTop(true);
        calc.show();
    }

    private double evalSimple(String s) {
        try {
            return ((Number) new javax.script.ScriptEngineManager()
                .getEngineByName("JavaScript").eval(s)).doubleValue();
        } catch (Exception e) {
            return 0;
        }
    }

    // =========================================================================
    //  FORMULA SHEET
    // =========================================================================
    @FXML private void openFormulaSheet() {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle("Formula Sheet");
        a.setHeaderText("Formula Sheet - KNOWLEDGE LAND COLLEGE");
        a.setContentText(formulaSheetText);
        a.getDialogPane().setPrefWidth(420);
        a.show();
    }

    // =========================================================================
    //  TIMER
    // =========================================================================
    // KLC v1.0: periodic answer persistence - README promises answers are
    // saved every 30 seconds, not only on navigation.
    private void startAutosave() {
        stopAutosave();
        autosaveTimer = new Timeline(
            new KeyFrame(Duration.seconds(30), e -> saveCurrent()));
        autosaveTimer.setCycleCount(Timeline.INDEFINITE);
        autosaveTimer.play();
    }

    private void stopAutosave() {
        if (autosaveTimer != null) { autosaveTimer.stop(); autosaveTimer = null; }
    }

    private void startTimer() {
        timer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            timeLeft--;
            timerLabel.setText(
                String.format("%02d:%02d:%02d",
                timeLeft / 3600, (timeLeft % 3600) / 60, timeLeft % 60));
            if (timerLabel.getStyle() == null
                    || !timerLabel.getStyle().contains("red")) {
                timerLabel.setStyle("-fx-text-fill:#c0392b;" +
                                    "-fx-font-size:22px;" +
                                    "-fx-font-weight:bold;");
            }
            if (timeLeft == 900) alert("15 minutes remaining!");
            if (timeLeft == 300) alert("5 minutes remaining!");
            if (timeLeft <= 0)   submitExam(false);
        }));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
    }

    // =========================================================================
    //  SUBMIT
    // =========================================================================
    @FXML private void submitExamClicked() {
        saveCurrent();
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "Are you sure you want to submit your exam?\n\n" +
            "Answered: " + answers.size() + " of " + questions.size() +
            " questions.\n\n" +
            "You CANNOT change your answers after submission.",
            ButtonType.YES, ButtonType.NO);
        confirm.setTitle("Submit Exam");
        confirm.setHeaderText("Confirm Submission");
        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.YES) submitExam(false);
        });
    }

    private void submitExam(boolean malpractice) {
        if (timer != null)         timer.stop();
        stopAutosave();
        if (webcamProctor != null) webcamProctor.stop();
        com.femzyk.klc.util.SyncService.stop();
        saveCurrent();
        // KLC v1.0: final answer push - keep retrying in the background
        // until every queued answer reaches the cloud (Nigeria network safe).
        com.femzyk.klc.util.SyncService.flushOnCloudReturn(null);
        saveCurrent();

        // FIX: Release exam lock so close button works again
        MainApp.examInProgress = false;

        String resultSummary = "";

        try (Connection c = DatabaseManager.getConnection()) {
            int correct = 0, wrong = 0, unanswered = 0;

            for (Question q : questions) {
                String sel = answers.get(q.id);
                if (sel == null) { unanswered++; continue; }

                String correctLabel = correctAnswerMap.get(q.id);
                if (correctLabel == null) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT option_label FROM question_options " +
                            "WHERE question_id=? AND is_correct=TRUE")) {
                        AuthService.setUuid(ps, 1, q.id, c);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) correctLabel = rs.getString(1);
                    }
                }
                if (sel.equals(correctLabel)) correct++; else wrong++;
            }

            double rawScore = Math.max(0,
                correct - (wrong * negativeMarking));
            double pct = questions.isEmpty()
                ? 0 : rawScore * 100.0 / questions.size();

            if (isPractice) {
                // Practice Mode: update attempt status only, DO NOT insert into official results table
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE exam_attempts " +
                        "SET submitted_at = CURRENT_TIMESTAMP, status = ? " +
                        "WHERE id = ?")) {
                    ps.setString(1, "PRACTICE_SUBMITTED");
                    AuthService.setUuid(ps, 2, attemptId, c);
                    ps.executeUpdate();
                }

                resultSummary = String.format(
                    "PRACTICE EXAM COMPLETED\n\n" +
                    "Correct:    %d\n" +
                    "Wrong:      %d\n" +
                    "Unanswered: %d\n" +
                    "Negative marking: %.2f per wrong answer\n\n" +
                    "PRACTICE SCORE:  %.1f / %d\n" +
                    "PERCENTAGE:      %.1f%%\n\n" +
                    "%s" +
                    "Well done, %s!\n" +
                    "Note: Practice results are NOT recorded on your permanent academic record.",
                    correct, wrong, unanswered, negativeMarking,
                    rawScore, questions.size(), pct,
                    topicBreakdownText(),
                    AuthService.Session.fullName);

            } else {
                // Official Exam Mode: update attempt status, save official result, and send email
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE exam_attempts " +
                        "SET submitted_at = CURRENT_TIMESTAMP, status = ? " +
                        "WHERE id = ?")) {
                    ps.setString(1, malpractice ? "MALPRACTICE" : "SUBMITTED");
                    AuthService.setUuid(ps, 2, attemptId, c);
                    ps.executeUpdate();
                }

                // KLC v1.0 FIX: README promises "account lockout" on the
                // 3rd proctoring strike. Lock the student account for 15
                // minutes (same window as the brute-force lockout in
                // AuthService) so the violation has consequences.
                if (malpractice) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE users SET failed_login_attempts = 5, " +
                            "locked_until = ? WHERE id = ?")) {
                        ps.setTimestamp(1, new Timestamp(
                            System.currentTimeMillis() + 15 * 60 * 1000L));
                        AuthService.setUuid(ps, 2,
                            AuthService.Session.userId, c);
                        ps.executeUpdate();
                    }
                    AuthService.logAudit("MALPRACTICE_LOCKOUT",
                        "users", AuthService.Session.userId);
                }

                // Insert result
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO results(" +
                        "id, attempt_id, student_id, exam_id, score, " +
                        "total_questions, correct_answers, percentage) " +
                        "VALUES(?,?,?,?,?,?,?,?)")) {
                    AuthService.setUuid(ps, 1, UUID.randomUUID().toString(), c);
                    AuthService.setUuid(ps, 2, attemptId, c);
                    AuthService.setUuid(ps, 3, AuthService.Session.userId, c);
                    AuthService.setUuid(ps, 4, examId, c);
                    ps.setDouble(5, rawScore);
                    ps.setInt   (6, questions.size());
                    ps.setInt   (7, correct);
                    ps.setDouble(8, pct);
                    ps.executeUpdate();
                }

                // Email notification (best-effort)
                try {
                    String email = null;
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT email FROM users WHERE id=?")) {
                        AuthService.setUuid(ps, 1, AuthService.Session.userId, c);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) email = rs.getString(1);
                    }
                    if (email != null) {
                        String title = examTitleLabel != null
                            ? examTitleLabel.getText() : "Exam";
                        com.femzyk.klc.util.EmailService
                            .sendResultNotification(email,
                                AuthService.Session.fullName, title, pct);
                    }
                } catch (Exception ignored) {}

                resultSummary = String.format(
                    "%s\n\n" +
                    "Correct:    %d\n" +
                    "Wrong:      %d\n" +
                    "Unanswered: %d\n" +
                    "Negative marking: %.2f per wrong answer\n\n" +
                    "RAW SCORE:  %.1f / %d\n" +
                    "PERCENTAGE: %.1f%%\n\n" +
                    "%s" +
                    "Well done, %s!\n" +
                    "You may now view your full result on your dashboard.",
                    malpractice
                        ? "EXAM AUTO-SUBMITTED (MALPRACTICE DETECTED)"
                        : "EXAM SUBMITTED SUCCESSFULLY",
                    correct, wrong, unanswered, negativeMarking,
                    rawScore, questions.size(), pct,
                    topicBreakdownText(),
                    AuthService.Session.fullName);
            }

        } catch (Exception e) {
            e.printStackTrace();
            resultSummary = "Exam submitted. Score calculation pending.";
        }

        // Exit fullscreen
        Stage st = (Stage) timerLabel.getScene().getWindow();
        st.setFullScreen(false);

        // FIX: Show result dialog THEN redirect to dashboard when OK clicked
        Alert resultAlert = new Alert(Alert.AlertType.INFORMATION);
        resultAlert.setTitle("Exam Result");
        resultAlert.setHeaderText(isPractice ? "Practice Result" : "Your Result");
        resultAlert.setContentText(resultSummary);
        resultAlert.getDialogPane().setPrefWidth(480);
        resultAlert.showAndWait();

        // After student clicks OK on result - go to dashboard
        Platform.runLater(() -> {
            try {
                MainApp.setRoot("student_dashboard.fxml", null);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });
    }

    private void alert(String m) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, m);
        a.getDialogPane().setPrefWidth(420);
        a.showAndWait();
    }
}
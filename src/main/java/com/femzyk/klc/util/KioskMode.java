package com.femzyk.klc.util;

import javafx.stage.Stage;

/**
 * KioskMode — the strongest exam lockdown achievable inside the app, layered
 * with the shipped Windows policy scripts (installer/windows/kiosk/).
 *
 * <p>Pure Java/JavaFX cannot intercept OS keys (Alt+Tab, Win, Task Manager) —
 * that is an OS boundary, not an app bug. The defence is therefore layered:
 * <ol>
 *   <li><b>This class (in-app):</b> true fullscreen with the exit hint/key
 *       disabled, always-on-top, and instant refocus+re-fullscreen if the
 *       window is iconified or loses fullscreen — so leaving the exam surface
 *       is immediately visible and focus-loss proctoring records a strike.</li>
 *   <li><b>FocusLossDetector (in-app):</b> 3 strikes → auto-submit + 15-min
 *       account lockout + audit trail with PC/IP.</li>
 *   <li><b>Windows kiosk policies (ops, shipped):</b>
 *       {@code Enable-KlcKiosk.ps1} locks the dedicated exam account at OS
 *       level (Task Manager / lock / sign-out disabled, app auto-launched,
 *       optional Assigned Access), {@code Disable-KlcKiosk.ps1} reverses it.
 *       That is the supported route to true OS lockdown on school PCs.</li>
 * </ol>
 */
public final class KioskMode {

    private KioskMode() {}

    /** Arm kiosk behaviour on the exam stage. Official exams only. */
    public static void arm(Stage stage) {
        if (stage == null) return;
        stage.setFullScreenExitHint("");
        stage.setFullScreenExitKeyCombination(javafx.scene.input.KeyCombination.NO_MATCH);
        stage.setAlwaysOnTop(true);
        stage.setFullScreen(true);
        // If anything pulls the exam out of fullscreen/iconifies it, pull it
        // straight back — FocusLossDetector records the strike separately.
        stage.fullScreenProperty().addListener((o, was, is) -> {
            if (!is && com.femzyk.klc.MainApp.examInProgress) {
                javafx.application.Platform.runLater(() -> stage.setFullScreen(true));
            }
        });
        stage.iconifiedProperty().addListener((o, was, is) -> {
            if (is && com.femzyk.klc.MainApp.examInProgress) {
                javafx.application.Platform.runLater(() -> {
                    stage.setIconified(false);
                    stage.setFullScreen(true);
                    stage.toFront();
                });
            }
        });
    }

    /** Release kiosk behaviour after submission. */
    public static void disarm(Stage stage) {
        if (stage == null) return;
        stage.setAlwaysOnTop(false);
        stage.setFullScreen(false);
    }
}

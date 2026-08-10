package com.femzyk.klc.auth;

import com.femzyk.klc.MainApp;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.Scene;
import javafx.scene.input.InputEvent;
import javafx.util.Duration;

/**
 * KLC Session Idle Timeout - 30 min
 * Auto-logout on inactivity - security compliance
 */
public class SessionIdleWatcher {
    private static Timeline ticker;
    private static final long TIMEOUT_MS = 30 * 60 * 1000L; // 30 min
    private static Runnable onTimeout;

    public static void start(Scene scene, Runnable onTimeoutAction){
        onTimeout = onTimeoutAction;
        // touch on any input
        scene.addEventFilter(InputEvent.ANY, e -> AuthService.Session.touch());
        if(ticker != null) ticker.stop();
        ticker = new Timeline(new KeyFrame(Duration.seconds(30), e -> check()));
        ticker.setCycleCount(Timeline.INDEFINITE);
        ticker.play();
    }
    private static void check(){
        if(AuthService.Session.userId == null) return;
        long idle = System.currentTimeMillis() - AuthService.Session.lastActivity;
        if(idle > TIMEOUT_MS){
            if(ticker != null) ticker.stop();
            javafx.application.Platform.runLater(() -> {
                try{
                    AuthService.logAudit("SESSION_TIMEOUT", "users", AuthService.Session.userId);
                    AuthService.Session.clear();
                    MainApp.setRoot("login.fxml", null);
                    new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.WARNING,
                        "Session timed out after 30 minutes of inactivity.\nPlease login again.\n\nKNOWLEDGE LAND COLLEGE").show();
                }catch(Exception ignored){}
                if(onTimeout != null) onTimeout.run();
            });
        }
    }
    public static void stop(){ if(ticker != null) ticker.stop(); }
}

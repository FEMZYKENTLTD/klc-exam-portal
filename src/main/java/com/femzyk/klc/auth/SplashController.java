package com.femzyk.klc.auth;

import com.femzyk.klc.MainApp;
import com.femzyk.klc.db.DatabaseManager;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.util.Duration;

public class SplashController {
    @FXML private Label statusLabel;

    @FXML public void initialize() {
        new Thread(() -> {
            update("Initializing Cloud Database…");
            DatabaseManager.init();
            sleep(400);
            update("Connecting to Supabase - knowledgeland …");
            sleep(500);
            update("Loading School Profile - KNOWLEDGE LAND COLLEGE");
            sleep(400);
            update("Security: BCrypt / Proctoring 3-Strike / Audit Trail");
            sleep(400);
            update("Ready - Powered by FEMZYK");
            sleep(300);
            Platform.runLater(() -> {
                try { MainApp.setRoot("login.fxml", null); } catch(Exception e){ e.printStackTrace(); }
            });
        }).start();
    }
    private void update(String s){ Platform.runLater(() -> { if(statusLabel!=null) statusLabel.setText(s); });}
    private void sleep(int ms){ try{ Thread.sleep(ms);} catch(Exception ignored){} }
}

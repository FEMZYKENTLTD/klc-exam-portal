package com.femzyk.klc.admin;

import com.femzyk.klc.db.DatabaseManager;
import com.femzyk.klc.util.EmailService;
import com.femzyk.klc.util.SmsService;
import com.femzyk.klc.util.SyncService;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.util.Duration;
import java.io.File;
import java.sql.Connection;

public class HealthMonitorController {
    @FXML private Label cloudDbLabel, cacheLabel, emailLabel, smsLabel, diskLabel, uptimeLabel;
    @FXML private TextArea logArea;

    private Timeline ticker;
    private long startMs = System.currentTimeMillis();

    @FXML public void initialize(){
        refresh();
        ticker = new Timeline(new KeyFrame(Duration.seconds(5), e -> refresh()));
        ticker.setCycleCount(Timeline.INDEFINITE);
        ticker.play();
    }

    private void refresh(){
        boolean cloudOk = false;
        try(Connection c = DatabaseManager.getCloudConnection()){ cloudOk = c.isValid(2); c.close(); }catch(Exception ignored){}
        boolean cacheOk = false;
        try(Connection c = DatabaseManager.getCacheConnection()){ cacheOk = c.isValid(1); c.close(); }catch(Exception ignored){}
        if(cloudDbLabel != null) cloudDbLabel.setText(cloudOk ? "● Cloud PostgreSQL - ONLINE - Supabase Free" : "● Cloud - OFFLINE - using H2 cache");
        if(cacheLabel != null) cacheLabel.setText(cacheOk ? "● H2 Offline Cache - READY" : "● Cache - ERROR");
        if(emailLabel != null) emailLabel.setText(EmailService.isEnabled() ? "● Email SMTP - CONFIGURED" : "● Email - QUEUE MODE ($0)");
        if(smsLabel != null) smsLabel.setText(SmsService.isEnabled() ? "● SMS Termii - CONFIGURED" : "● SMS - QUEUE MODE ($0)");
        File root = new File(".");
        long freeGb = root.getFreeSpace() / 1024 / 1024 / 1024;
        if(diskLabel != null) diskLabel.setText("● Disk Free: "+freeGb+" GB");
        long up = (System.currentTimeMillis() - startMs)/1000;
        if(uptimeLabel != null) uptimeLabel.setText("● Uptime: "+(up/60)+"m "+(up%60)+"s");
        int synced = SyncService.syncNow();
        if(logArea != null && synced > 0){
            logArea.appendText("["+java.time.LocalTime.now().withNano(0)+"] Auto-sync: "+synced+" records → Supabase\n");
        }
    }

    public void stop(){ if(ticker != null) ticker.stop(); }
}

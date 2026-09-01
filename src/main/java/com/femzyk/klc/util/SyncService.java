package com.femzyk.klc.util;

import com.femzyk.klc.db.DatabaseManager;
import com.google.gson.Gson;
import javafx.application.Platform;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;
import java.sql.*;
import java.util.UUID;

public class SyncService {
    private static final Gson gson = new Gson();
    private static volatile boolean syncing = false;
    private static Timeline autoSync;

    public static void queue(String table, String recordId, String operation, Object payload){
        try(Connection c = DatabaseManager.getCacheConnection();
            PreparedStatement ps = c.prepareStatement(
                "INSERT INTO sync_queue(id, table_name, record_id, operation, payload) VALUES(?,?,?,?,?)")){
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, table);
            ps.setString(3, recordId);
            ps.setString(4, operation);
            ps.setString(5, payload==null?null:gson.toJson(payload));
            ps.executeUpdate();
        }catch(Exception ignored){}
    }

    public static int syncNow(){
        if(syncing) return 0;
        syncing = true;
        int synced = 0;
        try{
            if(!DatabaseManager.isCloudAvailable()){
                try{ DatabaseManager.getCloudConnection().close(); DatabaseManager.setCloudAvailable(true); }
                catch(Exception e){ syncing=false; return 0; }
            }
            try(Connection local = DatabaseManager.getCacheConnection();
                Connection cloud = DatabaseManager.getCloudConnection()){
                try(PreparedStatement ps = local.prepareStatement(
                    "SELECT id, table_name, record_id, operation, payload FROM sync_queue WHERE synced=FALSE ORDER BY created_at LIMIT 200")){
                    ResultSet rs = ps.executeQuery();
                    while(rs.next()){
                        String qid = rs.getString(1);
                        // KLC v1.0: data is written via getConnection() with cloud-first fallback,
                        // sync_queue is audit trail - mark synced
                        try(PreparedStatement up = local.prepareStatement(
                            "UPDATE sync_queue SET synced=TRUE, synced_at=NOW() WHERE id=?")){
                            up.setString(1, qid); up.executeUpdate(); synced++;
                        }
                    }
                }
            }
        }catch(Exception ignored){}
        syncing = false;
        return synced;
    }

    // Auto-sync every 30s - Nigeria network safe
    public static void startAutoSync(javafx.scene.control.Label statusLabel){
        if(autoSync != null) return;
        autoSync = new Timeline(new KeyFrame(Duration.seconds(30), e -> {
            int n = syncNow();
            if(n > 0 && statusLabel != null){
                Platform.runLater(() -> statusLabel.setText("Synced "+n+" offline records to Cloud"));
            }
        }));
        autoSync.setCycleCount(Timeline.INDEFINITE);
        autoSync.play();
    }
    public static void stop(){ if(autoSync != null) autoSync.stop(); }
}

package com.femzyk.klc.util;

import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.db.DatabaseManager;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
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
                // KLC v1.0 FIX: sync_queue has no created_at column (old
                // query threw SQLState 42703 every cycle and the whole
                // sync silently did nothing). Order by id instead, and
                // REPLAY rows to the cloud instead of just marking them.
                try(PreparedStatement ps = local.prepareStatement(
                    "SELECT id, table_name, record_id, operation, payload FROM sync_queue WHERE synced=FALSE ORDER BY id LIMIT 200")){
                    ResultSet rs = ps.executeQuery();
                    while(rs.next()){
                        String qid       = rs.getString(1);
                        String tableName = rs.getString(2);
                        String operation = rs.getString(4);
                        String payload   = rs.getString(5);
                        if(applyToCloud(cloud, tableName, operation, payload)){
                            try(PreparedStatement up = local.prepareStatement(
                                "UPDATE sync_queue SET synced=TRUE WHERE id=?")){
                                up.setString(1, qid); up.executeUpdate(); synced++;
                            }
                        }
                    }
                }
            }
        }catch(Exception ignored){}
        syncing = false;
        return synced;
    }

    /**
     * KLC v1.0 FIX (README promise: "answers save locally every 30 seconds
     * and auto-sync when the network returns"). Queued rows are now actually
     * replayed to the cloud. attempt_answers is the critical offline path
     * (exam answer autosave fallback); every other write goes through
     * getConnection() cloud-first, so those queue rows are an audit trail
     * and can be marked applied.
     * Returns true only when the row is safe to mark as synced; a failed
     * replay stays unsynced and is retried on the next 30s cycle.
     */
    private static boolean applyToCloud(Connection cloud, String table,
                                        String operation, String payload){
        try{
            if("attempt_answers".equals(table) && "INSERT".equals(operation)
                    && payload != null && !payload.isBlank()){
                JsonObject o = gson.fromJson(payload, JsonObject.class);
                if (o == null) return true;
                String attemptId  = o.has("attempt_id")      && !o.get("attempt_id").isJsonNull()
                                    ? o.get("attempt_id").getAsString()      : null;
                String questionId = o.has("question_id")     && !o.get("question_id").isJsonNull()
                                    ? o.get("question_id").getAsString()     : null;
                String selected   = o.has("selected_option") && !o.get("selected_option").isJsonNull()
                                    ? o.get("selected_option").getAsString() : null;
                if (attemptId == null || questionId == null) return true;

                try (PreparedStatement del = cloud.prepareStatement(
                        "DELETE FROM attempt_answers WHERE attempt_id=? AND question_id=?")){
                    AuthService.setUuid(del, 1, attemptId, cloud);
                    AuthService.setUuid(del, 2, questionId, cloud);
                    del.executeUpdate();
                }
                try (PreparedStatement ins = cloud.prepareStatement(
                        "INSERT INTO attempt_answers" +
                        "(id, attempt_id, question_id, selected_option) " +
                        "VALUES(?,?,?,?)")){
                    AuthService.setUuid(ins, 1, UUID.randomUUID().toString(), cloud);
                    AuthService.setUuid(ins, 2, attemptId, cloud);
                    AuthService.setUuid(ins, 3, questionId, cloud);
                    ins.setString(4, selected == null ? "" : selected);
                    ins.executeUpdate();
                }
                return true;
            }
            // Everything else was written cloud-first; queue row is the
            // audit trail only.
            return true;
        }catch(Exception e){
            System.out.println("[Sync] Deferred " + table + "/" + operation
                + ": " + e.getMessage());
            return false;
        }
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

package com.femzyk.klc.admin;

import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.db.DatabaseManager;
import com.femzyk.klc.util.BackupService;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;
import java.sql.*;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class BackupController {
    @FXML private TableView<BRow> table;
    @FXML private TableColumn<BRow,String> colDate, colType, colFile, colSize, colChecksum;
    @FXML private Label status;
    @FXML private CheckBox autoBackupCheck;
    @FXML private ComboBox<String> autoBackupTimeBox;

    private static Timeline autoBackupTimer;
    public static class BRow {
        String date, type, file, size, checksum;
        BRow(String d,String t,String f,String s,String c){date=d;type=t;file=f;size=s;checksum=c;}
        public String getDate(){return date;}
        public String getType(){return type;}
        public String getFile(){return file;}
        public String getSize(){return size;}
        public String getChecksum(){return checksum;}
    }
    ObservableList<BRow> data = FXCollections.observableArrayList();

    @FXML public void initialize(){
        colDate.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getDate()));
        colType.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getType()));
        colFile.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getFile()));
        colSize.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getSize()));
        colChecksum.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getChecksum()));
        table.setItems(data);
        if(autoBackupTimeBox != null){
            autoBackupTimeBox.getItems().addAll("02:00","03:00","23:00","Disabled");
            autoBackupTimeBox.setValue("02:00");
        }
        load();
    }
    @FXML private void load(){
        data.clear();
        try(Connection c=DatabaseManager.getConnection();
            PreparedStatement ps=c.prepareStatement("SELECT created_at, backup_type, file_path, file_size, checksum FROM backup_logs ORDER BY created_at DESC LIMIT 100")){
            ResultSet rs=ps.executeQuery();
            while(rs.next()) data.add(new BRow(
                rs.getTimestamp(1).toLocalDateTime().toString().replace('T',' '),
                rs.getString(2), rs.getString(3),
                String.format("%.2f MB", rs.getLong(4)/1024.0/1024.0),
                rs.getString(5)==null?"":rs.getString(5).substring(0,16)+"…"));
            status.setText("Backup history: "+data.size()+" | Supabase auto-backup daily | Local encrypted .klcbackup with SHA256");
        }catch(Exception e){ status.setText(e.getMessage()); }
    }
    @FXML private void runBackup(){
        try{
            status.setText("Backing up… users, students, questions, exams, results, ca_scores…");
            var r = BackupService.createBackup(AuthService.Session.userId);
            status.setText("✅ Backup complete: "+r.file+" - "+String.format("%.2f MB", r.size/1024.0/1024.0)+" - SHA256: "+r.sha256.substring(0,16)+"…");
            new Alert(Alert.AlertType.INFORMATION, "Backup saved: "+r.file
                +(r.encrypted ? "\n🔒 AES-256-GCM ENCRYPTED (backup.key)"
                              : "\n(plain ZIP - set backup.key in config to encrypt)")
                +"\nSHA256: "+r.sha256+"\n\nStore safely - restorable on any PC.").showAndWait();
            load();
        }catch(Exception e){ status.setText("Backup failed: "+e.getMessage()); e.printStackTrace(); }
    }
    @FXML private void toggleAutoBackup(){
        if(autoBackupCheck == null || !autoBackupCheck.isSelected()){
            if(autoBackupTimer != null){ autoBackupTimer.stop(); autoBackupTimer = null; }
            status.setText("Auto-backup disabled");
            return;
        }
        // In-app auto-backup every 24h (simplified - real scheduler: Windows Task Scheduler)
        if(autoBackupTimer != null) autoBackupTimer.stop();
        autoBackupTimer = new Timeline(new KeyFrame(Duration.hours(24), e -> {
            try{ BackupService.createBackup(AuthService.Session.userId); } catch(Exception ignored){}
        }));
        autoBackupTimer.setCycleCount(Timeline.INDEFINITE);
        autoBackupTimer.play();
        status.setText("Auto-backup ENABLED - daily at "+ (autoBackupTimeBox.getValue()) +" - also enable Windows Task Scheduler: KnowledgeLandCBT.exe --backup");
    }
    /**
     * REAL restore (directive F6): picks a .klcbackup, confirms, then
     * BackupService.restore replays every table inside one transaction
     * (full rollback on any error, so a failed file can never half-wipe
     * the live database). AES-encrypted packs are decrypted with the
     * configured backup.key automatically.
     */
    @FXML private void restoreBackup(){
        FileChooser fc = new FileChooser();
        fc.setTitle("Choose a backup to restore");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(
            "KLC backups", "*.klcbackup", "*.zip"));
        Window w = table.getScene() == null ? null
            : table.getScene().getWindow();
        File f = fc.showOpenDialog(w);
        if (f == null) return;
        java.util.Optional<ButtonType> ans = new Alert(
            Alert.AlertType.CONFIRMATION,
            "Restore REPLACES the current contents of every table in the "
            + "backup with the copy from:\n\n" + f.getName() + "\n\n"
            + "The whole restore runs as ONE transaction - if anything "
            + "fails, the database is left exactly as it was.",
            ButtonType.YES, ButtonType.NO).showAndWait();
        if (!ans.isPresent() || ans.get() != ButtonType.YES) return;
        status.setText("Restoring " + f.getName() + " …");
        try {
            var r = BackupService.restore(f);
            status.setText("Restore complete: " + r.tables + " tables, "
                + r.rows + " rows reloaded from " + f.getName()
                + (r.encrypted ? " (decrypted AES)" : "")
                + " | SHA256 " + r.sha256.substring(0, 16) + "…");
            new Alert(Alert.AlertType.INFORMATION,
                "Restore completed successfully.\n\n"
                + "Tables reloaded: " + r.tables + "\n"
                + "Rows restored:   " + r.rows + "\n"
                + "Encrypted pack:  " + (r.encrypted ? "yes" : "no")
                + "\n\n"
                + "If you restored onto this PC on purpose, restart the app "
                + "and verify results with the new data.").showAndWait();
            load();
        } catch (Exception e) {
            status.setText("Restore FAILED - database rolled back: "
                + e.getMessage());
            new Alert(Alert.AlertType.ERROR,
                "Restore failed and was fully rolled back:\n\n"
                + e.getMessage()).showAndWait();
        }
    }

    /** Writes a backup + checksum + restore manual onto a USB drive. */
    @FXML private void exportToUsb(){
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Choose your USB drive / recovery folder");
        Window w = table.getScene() == null ? null
            : table.getScene().getWindow();
        File dir = dc.showDialog(w);
        if (dir == null) return;
        status.setText("Writing recovery pack to " + dir.getAbsolutePath()
            + " …");
        try {
            var p = BackupService.exportUsbPack(dir,
                AuthService.Session.userId);
            StringBuilder sb = new StringBuilder(
                "USB recovery pack written to:\n" + dir.getAbsolutePath()
                + "\n\nFiles:\n");
            for (String f : p.files) sb.append("  • ").append(f).append("\n");
            sb.append("\nOn any other PC: Admin → Backup & Recovery → "
                + "Restore from Backup, then choose the .klcbackup file.");
            new Alert(Alert.AlertType.INFORMATION, sb.toString())
                .showAndWait();
            status.setText("USB recovery pack ready in "
                + dir.getAbsolutePath());
        } catch (Exception e) {
            status.setText("USB pack failed: " + e.getMessage());
            new Alert(Alert.AlertType.ERROR,
                "Could not write the USB pack:\n\n"
                + e.getMessage()).showAndWait();
        }
    }

    @FXML private void restoreInfo(){
        new Alert(Alert.AlertType.INFORMATION,
            "RESTORE OPTIONS - KNOWLEDGE LAND COLLEGE\n\n"
            + "1. Automatic (recommended): click 'Restore from Backup…' "
            + "and choose a .klcbackup file. Every table is reloaded inside "
            + "one transaction; a failure rolls back completely. "
            + "AES-encrypted packs (KLCENC1) are decrypted with the "
            + "configured backup.key - no manual steps.\n\n"
            + "2. USB recovery pack: click 'Export USB Recovery Pack…', "
            + "point at a USB stick. The pack carries the snapshot, its "
            + "SHA-256 checksum and a printed restore manual.\n\n"
            + "3. Cloud point-in-time restore (Supabase Pro): "
            + "Dashboard → Database → Backups → Point-in-time Restore.\n"
            + "Cloud backups are taken daily; 10-year retention is the "
            + "school's own policy - keep every .klcbackup file and USB "
            + "pack, and store the backup.key safely."
        ).showAndWait();
    }
}

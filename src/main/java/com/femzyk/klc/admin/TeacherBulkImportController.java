package com.femzyk.klc.admin;

import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.db.DatabaseManager;
import com.femzyk.klc.util.PasswordGen;
import com.opencsv.CSVReader;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.UUID;
import at.favre.lib.crypto.bcrypt.BCrypt;

public class TeacherBulkImportController {
    @FXML private TextArea logArea;
    @FXML private Label status;

    @FXML private void importTeachers(){
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV","*.csv"));
        var file = fc.showOpenDialog(logArea.getScene().getWindow());
        if(file==null) return;
        int imported=0;
        StringBuilder log = new StringBuilder();
        // KLC v1.0 security fix: no fixed default password (the old one was
        // published in the repo). Uses import.default_password from
        // config.properties when set, else a random one-time password shown
        // once in the status line after the import.
        String defaultPw = PasswordGen.defaultImportPassword();
        try(Connection c=DatabaseManager.getConnection(); CSVReader r=new CSVReader(new FileReader(file, StandardCharsets.UTF_8))){
            String[] header = r.readNext();
            String[] row;
            while((row = r.readNext()) != null){
                String fullName = get(row, header, "full_name",0);
                String email = get(row, header, "email",1);
                String subjectsText = get(row, header, "subjects",2); // e.g. DTP-SS1;DTP-SS2;DTP-SS3
                if(email.isBlank() || fullName.isBlank()) continue;
                // check exists
                try(PreparedStatement ps=c.prepareStatement("SELECT 1 FROM users WHERE email=?")){
                    ps.setString(1, email.toLowerCase()); if(ps.executeQuery().next()){ log.append("Skip exists: ").append(email).append("\n"); continue; }
                }
                String userId = UUID.randomUUID().toString();
                String hash = BCrypt.withDefaults().hashToString(12, defaultPw.toCharArray());
                try(PreparedStatement ps=c.prepareStatement("INSERT INTO users(id, full_name, email, password_hash, role) VALUES(?,?,?,?,?)")){
                    ps.setObject(1, UUID.fromString(userId)); ps.setString(2, fullName); ps.setString(3, email.toLowerCase());
                    ps.setString(4, hash); ps.setString(5, "TEACHER"); ps.executeUpdate();
                }
                // assign subjects
                if(!subjectsText.isBlank()){
                    for(String code: subjectsText.split("[;,\\s]+")){
                        code=code.trim().toUpperCase(); if(code.isEmpty()) continue;
                        String subjectId=null;
                        try(PreparedStatement ps=c.prepareStatement("SELECT id FROM subjects WHERE subject_code=?")){
                            ps.setString(1, code); ResultSet rs=ps.executeQuery(); if(rs.next()) subjectId=rs.getString(1);
                        }
                        if(subjectId!=null){
                            try(PreparedStatement ps=c.prepareStatement("INSERT INTO teacher_subjects(teacher_id, subject_id, assigned_by) VALUES(?,?,?)")){
                                ps.setObject(1, UUID.fromString(userId)); ps.setObject(2, UUID.fromString(subjectId));
                                ps.setObject(3, UUID.fromString(AuthService.Session.userId)); ps.executeUpdate();
                            }
                        }
                    }
                }
                imported++; log.append("Imported: ").append(fullName).append(" - ").append(email).append("\n");
            }
            status.setText("Imported "+imported+" teachers. One-time default password: "+defaultPw
                +" - tell each teacher to change it at first login."
                +" (Set import.default_password in config.properties to fix this value.)");
            logArea.setText(log.toString());
        }catch(Exception e){ status.setText(e.getMessage()); e.printStackTrace(); }
    }
    private String get(String[] row, String[] header, String key, int fb){
        if(header!=null){ for(int i=0;i<header.length && i<row.length;i++) if(header[i].trim().equalsIgnoreCase(key)) return row[i].trim(); }
        return fb < row.length ? row[fb].trim() : "";
    }
}

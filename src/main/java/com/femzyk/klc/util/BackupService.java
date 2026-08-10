package com.femzyk.klc.util;

import java.util.UUID;
import com.femzyk.klc.db.DatabaseManager;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class BackupService {
    public static class BackupResult { public String file; public long size; public String sha256; }

    public static BackupResult createBackup(String userId) throws Exception {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String zipName = "KLC_backup_"+stamp+".klcbackup";
        try(Connection c = DatabaseManager.getConnection();
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipName))){

            String[] tables = {"users","student_profiles","subjects","questions","question_options","exams","results","ca_scores","school_profile","audit_logs"};
            for(String tbl: tables){
                zos.putNextEntry(new ZipEntry(tbl+".csv"));
                try(Statement st=c.createStatement(); ResultSet rs=st.executeQuery("SELECT * FROM "+tbl+" LIMIT 5000")){
                    ResultSetMetaData md = rs.getMetaData();
                    int cols = md.getColumnCount();
                    // header
                    StringBuilder h = new StringBuilder();
                    for(int i=1;i<=cols;i++){ if(i>1)h.append(","); h.append(md.getColumnName(i)); }
                    h.append("\n"); zos.write(h.toString().getBytes());
                    while(rs.next()){
                        StringBuilder sb = new StringBuilder();
                        for(int i=1;i<=cols;i++){
                            if(i>1) sb.append(",");
                            String v = rs.getString(i);
                            if(v!=null){ v=v.replace("\"","\"\""); if(v.contains(",")||v.contains("\n")) v="\""+v+"\""; sb.append(v); }
                        }
                        sb.append("\n");
                        zos.write(sb.toString().getBytes());
                    }
                }
                zos.closeEntry();
            }
            // manifest
            zos.putNextEntry(new ZipEntry("manifest.txt"));
            String manifest = "KNOWLEDGE LAND COLLEGE CBT Backup\nVersion: 6.2\nDate: "+stamp+"\nSchool: KNOWLEDGE LAND COLLEGE\nPowered by FEMZYK\nLead: OLUFEMI BENUA KERIPE\n";
            zos.write(manifest.getBytes());
            zos.closeEntry();
        }
        Path p = Paths.get(zipName);
        long size = Files.size(p);
        byte[] bytes = Files.readAllBytes(p);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(bytes);
        StringBuilder sb = new StringBuilder();
        for(byte b: hash) sb.append(String.format("%02x", b));
        // log
        try(Connection c = DatabaseManager.getConnection();
            PreparedStatement ps=c.prepareStatement("INSERT INTO backup_logs(backup_type, file_path, file_size, checksum, created_by) VALUES(?,?,?,?,?)")){
            ps.setString(1, "FULL_CSV");
            ps.setString(2, zipName);
            ps.setLong(3, size);
            ps.setString(4, sb.toString());
            ps.setObject(5, userId==null?null:UUID.fromString(userId));
            ps.executeUpdate();
        }catch(Exception ignored){}
        BackupResult r = new BackupResult();
        r.file = zipName; r.size = size; r.sha256 = sb.toString();
        return r;
    }
}

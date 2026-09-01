package com.femzyk.klc.util;

import java.util.UUID;
import com.femzyk.klc.auth.AuthService;
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
    public static class BackupResult { public String file; public long size; public String sha256; public boolean encrypted; }

    // KLC v1.0 (spec 9.3/10): encrypted backups - when backup.key is set
    // in config.properties the .klcbackup is AES-GCM encrypted with the
    // magic header KLCENC1. Without a key the file stays a plain ZIP.
    static final byte[] MAGIC = "KLCENC1".getBytes();

    /** Decrypt an encrypted .klcbackup back to a plain ZIP file. */
    public static File decryptToZip(File encFile, File destZip) throws Exception {
        byte[] all = Files.readAllBytes(encFile.toPath());
        int headerLen = MAGIC.length + 12 + 16; // magic + IV + tag is inside
        if (all.length < headerLen || !startsWith(all, MAGIC))
            throw new IllegalArgumentException("Not an encrypted KLC backup");
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(all);
        byte[] magic = new byte[MAGIC.length]; buf.get(magic);
        byte[] iv = new byte[12]; buf.get(iv);
        byte[] cipher = new byte[buf.remaining()]; buf.get(cipher);

        javax.crypto.SecretKey key = backupKey();
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        c.init(javax.crypto.Cipher.DECRYPT_MODE, key,
            new javax.crypto.spec.GCMParameterSpec(128, iv));
        byte[] plain = c.doFinal(cipher);
        Files.write(destZip.toPath(), plain);
        return destZip;
    }

    static boolean isEncrypted(File f) throws IOException {
        byte[] head = new byte[MAGIC.length];
        try (InputStream in = new FileInputStream(f)) {
            if (in.read(head) != MAGIC.length) return false;
        }
        return startsWith(head, MAGIC);
    }

    private static boolean startsWith(byte[] a, byte[] prefix) {
        if (a.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++)
            if (a[i] != prefix[i]) return false;
        return true;
    }

    private static javax.crypto.SecretKey backupKey() throws Exception {
        String key = ConfigService.get("backup.key", "");
        if (key.isBlank())
            throw new IllegalStateException(
                "backup.key is not set in config.properties");
        byte[] k = java.security.MessageDigest.getInstance("SHA-256")
            .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new javax.crypto.spec.SecretKeySpec(k, "AES");
    }

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
            String manifest = "KNOWLEDGE LAND COLLEGE CBT Backup\nVersion: 1.0\nDate: "+stamp+"\nSchool: KNOWLEDGE LAND COLLEGE\nPowered by FEMZYK\nLead: OLUFEMI BENUA KERIPE\n";
            zos.write(manifest.getBytes());
            zos.closeEntry();
        }

        // KLC v1.0: optional AES-GCM encryption (backup.key in config)
        boolean encrypted = false;
        try {
            String keyProp = ConfigService.get("backup.key", "");
            if (!keyProp.isBlank()) {
                byte[] zipBytes = Files.readAllBytes(Paths.get(zipName));
                byte[] iv = new byte[12];
                new java.security.SecureRandom().nextBytes(iv);
                javax.crypto.Cipher c = javax.crypto.Cipher.getInstance(
                    "AES/GCM/NoPadding");
                c.init(javax.crypto.Cipher.ENCRYPT_MODE, backupKey(),
                    new javax.crypto.spec.GCMParameterSpec(128, iv));
                byte[] cipher = c.doFinal(zipBytes);
                byte[] out = new byte[MAGIC.length + 12 + cipher.length];
                System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
                System.arraycopy(iv, 0, out, MAGIC.length, 12);
                System.arraycopy(cipher, 0, out, MAGIC.length + 12,
                    cipher.length);
                Files.write(Paths.get(zipName), out);
                encrypted = true;
            }
        } catch (Exception e) {
            System.out.println("[Backup] encryption skipped: " + e.getMessage());
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
            ps.setString(1, encrypted ? "FULL_CSV_AES" : "FULL_CSV");
            ps.setString(2, zipName);
            ps.setLong(3, size);
            ps.setString(4, sb.toString());
            // KLC v1.0 FIX: cross-DB UUID bind (raw UUID object broke H2)
            AuthService.setUuid(ps, 5, userId, c);
            ps.executeUpdate();
        }catch(Exception ignored){}
        BackupResult r = new BackupResult();
        r.file = zipName; r.size = size; r.sha256 = sb.toString();
        r.encrypted = encrypted;
        return r;
    }
}

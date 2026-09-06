package com.femzyk.klc.util;

import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.db.DatabaseManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * BackupService - full database snapshot to an encrypted (or plain) ZIP of
 * CSVs, with a REAL restore path and a USB "result pack" (directive F6:
 * backup / restore / USB pack / strongest practical recovery).
 *
 * Backup: every application table (except the bookkeeping tables
 * backup_logs and sync_queue) is dumped as {table}.csv, streaming (no row
 * cap), inside one .klcbackup file. When backup.key is set the ZIP is
 * AES-256-GCM encrypted with the KLCENC1 header.
 *
 * Restore: verify/decrypt the file, then replay the CSVs inside ONE
 * transaction into the connected database (the same database or a fresh
 * one). Foreign keys are suspended while rows are reloaded
 * (SET REFERENTIAL_INTEGRITY FALSE on H2, DISABLE TRIGGER ALL on
 * PostgreSQL) and re-enabled afterwards; any failure rolls the whole
 * restore back, so a bad file can never half-wipe a live database.
 *
 * USB pack: backup + SHA-256 checksum + short restore manual written onto
 * a removable drive directory, so recovery on another PC is one step.
 */
public class BackupService {

    public static class BackupResult {
        public String file; public long size; public String sha256;
        public boolean encrypted; public int tables;
    }

    public static class RestoreResult {
        public String source; public boolean encrypted;
        public int tables; public long rows; public String sha256;
    }

    public static class UsbPackResult {
        public String dir; public List<String> files = new ArrayList<>();
    }

    /** Tables that must not ride inside a backup (bookkeeping / replay). */
    private static final Set<String> SKIP_TABLES = new HashSet<>(List.of(
        "backup_logs", "sync_queue"));

    // KLC v1.0: AES-GCM encrypted backups carry this magic header.
    static final byte[] MAGIC = "KLCENC1".getBytes(StandardCharsets.UTF_8);

    private BackupService() {}

    // =========================================================================
    //  ENCRYPTION HELPERS
    // =========================================================================

    /** Decrypt an encrypted .klcbackup back to a plain ZIP file. */
    public static File decryptToZip(File encFile, File destZip)
            throws Exception {
        byte[] all = Files.readAllBytes(encFile.toPath());
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(all);
        byte[] magic = new byte[MAGIC.length];
        buf.get(magic);
        if (!startsWith(magic, MAGIC))
            throw new IllegalArgumentException("Not an encrypted KLC backup");
        byte[] iv = new byte[12]; buf.get(iv);
        byte[] cipher = new byte[buf.remaining()]; buf.get(cipher);

        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance(
            "AES/GCM/NoPadding");
        c.init(javax.crypto.Cipher.DECRYPT_MODE, backupKey(),
            new javax.crypto.spec.GCMParameterSpec(128, iv));
        byte[] plain = c.doFinal(cipher);
        Files.write(destZip.toPath(), plain);
        return destZip;
    }

    static boolean isEncrypted(File f) throws Exception {
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
        if (key == null || key.isBlank())
            throw new IllegalStateException(
                "backup.key is not set in config.properties");
        byte[] k = MessageDigest.getInstance("SHA-256")
            .digest(key.getBytes(StandardCharsets.UTF_8));
        return new javax.crypto.spec.SecretKeySpec(k, "AES");
    }

    private static String sha256Of(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] bytes = Files.readAllBytes(f.toPath());
        byte[] hash = md.digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // =========================================================================
    //  CREATE BACKUP
    // =========================================================================

    /** Creates a backup next to the working directory (default target). */
    public static BackupResult createBackup(String userId) throws Exception {
        String stamp = LocalDateTime.now().format(
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        File out = new File("KLC_backup_" + stamp + ".klcbackup");
        return createBackupTo(DatabaseManager.getConnection(), userId, out);
    }

    /** Core implementation - explicit connection + output file (testable). */
    static BackupResult createBackupTo(Connection conn, String userId,
            File outFile) throws Exception {
        String stamp = LocalDateTime.now().format(
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        List<String> tables = orderedTables(conn);
        File parent = outFile.getParentFile() == null
            ? new File(".") : outFile.getParentFile();
        File zip = File.createTempFile("klc_backup_tmp_", ".zip", parent);

        try (ZipOutputStream zos = new ZipOutputStream(
                new FileOutputStream(zip))) {
            for (String tbl : tables) {
                zos.putNextEntry(new ZipEntry(tbl + ".csv"));
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery(
                         "SELECT * FROM " + quote(tbl))) {
                    ResultSetMetaData md = rs.getMetaData();
                    int cols = md.getColumnCount();
                    StringBuilder h = new StringBuilder();
                    for (int i = 1; i <= cols; i++) {
                        if (i > 1) h.append(',');
                        h.append(md.getColumnName(i));
                    }
                    h.append('\n');
                    zos.write(h.toString().getBytes(StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    while (rs.next()) {
                        sb.setLength(0);
                        for (int i = 1; i <= cols; i++) {
                            if (i > 1) sb.append(',');
                            String v = rs.getString(i);
                            if (v != null) {
                                v = v.replace("\"", "\"\"");
                                if (v.contains(",") || v.contains("\n")
                                        || v.contains("\""))
                                    v = "\"" + v + "\"";
                                sb.append(v);
                            }
                        }
                        sb.append('\n');
                        zos.write(sb.toString()
                            .getBytes(StandardCharsets.UTF_8));
                    }
                }
                zos.closeEntry();
            }
            // manifest (school-facing text only - official documents and
            // backup artefacts stay free of vendor branding)
            zos.putNextEntry(new ZipEntry("manifest.txt"));
            String manifest =
                "KNOWLEDGE LAND COLLEGE CBT - database backup\n"
                + "Version: 1.0\nDate: " + stamp + "\n"
                + "Tables: " + tables.size() + "\n"
                + "Restore: open the app -> Admin -> Backup & Recovery -> "
                + "Restore from Backup and choose this file.\n"
                + "Integrity: SHA-256 checksum written at backup time.\n";
            zos.write(manifest.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        // Optional AES-256-GCM encryption when backup.key is configured.
        boolean encrypted = false;
        File finalFile = outFile;
        try {
            String keyProp = ConfigService.get("backup.key", "");
            if (keyProp != null && !keyProp.isBlank()) {
                byte[] zipBytes = Files.readAllBytes(zip.toPath());
                byte[] iv = new byte[12];
                new java.security.SecureRandom().nextBytes(iv);
                javax.crypto.Cipher c = javax.crypto.Cipher.getInstance(
                    "AES/GCM/NoPadding");
                c.init(javax.crypto.Cipher.ENCRYPT_MODE, backupKey(),
                    new javax.crypto.spec.GCMParameterSpec(128, iv));
                byte[] cipherBytes = c.doFinal(zipBytes);
                byte[] outBytes = new byte[MAGIC.length + 12
                    + cipherBytes.length];
                System.arraycopy(MAGIC, 0, outBytes, 0, MAGIC.length);
                System.arraycopy(iv, 0, outBytes, MAGIC.length, 12);
                System.arraycopy(cipherBytes, 0, outBytes,
                    MAGIC.length + 12, cipherBytes.length);
                if (!finalFile.getName().toLowerCase()
                        .endsWith(".klcbackup")) {
                    finalFile = new File(finalFile.getPath()
                        + ".klcbackup");
                }
                Files.write(finalFile.toPath(), outBytes);
                encrypted = true;
            } else {
                Files.move(zip.toPath(), finalFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            System.out.println("[Backup] encryption skipped: "
                + e.getMessage());
            if (!finalFile.getName().toLowerCase()
                    .endsWith(".klcbackup")
                    && !finalFile.getName().toLowerCase().endsWith(".zip")) {
                finalFile = new File(finalFile.getPath() + ".klcbackup");
            }
            Files.move(zip.toPath(), finalFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (zip.exists()) zip.delete();
        }

        long size = Files.size(finalFile.toPath());
        String sha = sha256Of(finalFile);
        logBackup(userId, encrypted ? "FULL_AES" : "FULL", finalFile,
            size, sha, conn);
        BackupResult r = new BackupResult();
        r.file = finalFile.getAbsolutePath(); r.size = size;
        r.sha256 = sha; r.encrypted = encrypted; r.tables = tables.size();
        return r;
    }

    // =========================================================================
    //  RESTORE
    // =========================================================================

    /** Restore a .klcbackup into the connected database (default target). */
    public static RestoreResult restore(File backupFile) throws Exception {
        RestoreResult r = restoreInto(backupFile,
            DatabaseManager.getConnection());
        try {
            logBackup(AuthService.Session.userId,
                r.encrypted ? "RESTORE_AES" : "RESTORE", backupFile,
                Files.size(backupFile.toPath()), r.sha256, null);
        } catch (Exception ignored) {}
        return r;
    }

    /** Core implementation - explicit target connection (testable). */
    static RestoreResult restoreInto(File backupFile, Connection target)
            throws Exception {
        if (backupFile == null || !backupFile.exists())
            throw new IllegalArgumentException(
                "Backup file not found: " + backupFile);

        boolean encrypted = isEncrypted(backupFile);
        File zipFile;
        if (encrypted) {
            zipFile = File.createTempFile("klc_restore_", ".zip");
            zipFile.deleteOnExit();
            decryptToZip(backupFile, zipFile);
        } else {
            zipFile = backupFile;
        }

        long rows = 0;
        int tables = 0;
        boolean oldAuto = target.getAutoCommit();
        target.setAutoCommit(false);
        boolean fkOff = false;
        try {
            fkOff = disableForeignKeys(target);
            try (ZipInputStream zis = new ZipInputStream(
                    new FileInputStream(zipFile))) {
                ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    if (e.isDirectory() || !e.getName().endsWith(".csv"))
                        continue;
                    String tbl = e.getName().substring(
                        0, e.getName().length() - 4);
                    if (SKIP_TABLES.contains(tbl)) continue;
                    Set<String> destCols = tableColumns(target, tbl);
                    if (destCols.isEmpty()) continue; // not in this schema

                    // header line
                    java.io.PushbackInputStream pin =
                        new java.io.PushbackInputStream(zis, 2);
                    List<String> cols = readCsvRecord(pin);
                    if (cols == null || cols.isEmpty()
                            || (cols.size() == 1 && cols.get(0).isBlank()))
                        continue;
                    List<String> usable = new ArrayList<>();
                    for (String c : cols) {
                        if (destCols.contains(c)) usable.add(c);
                    }
                    if (usable.isEmpty()) continue;
                    StringBuilder ins = new StringBuilder(
                        "INSERT INTO " + quote(tbl) + " (");
                    for (int i = 0; i < usable.size(); i++) {
                        if (i > 0) ins.append(',');
                        ins.append(quote(usable.get(i)));
                    }
                    ins.append(") VALUES (");
                    for (int i = 0; i < usable.size(); i++) {
                        if (i > 0) ins.append(',');
                        ins.append('?');
                    }
                    ins.append(')');

                    try (Statement st = target.createStatement()) {
                        st.execute("DELETE FROM " + quote(tbl));
                    }
                    try (PreparedStatement ps = target.prepareStatement(
                            ins.toString())) {
                        List<String> vals;
                        while ((vals = readCsvRecord(pin)) != null) {
                            if (vals.size() == 1 && vals.get(0).isEmpty())
                                continue; // blank line
                            for (int i = 0; i < usable.size(); i++) {
                                String v = i < vals.size() ? vals.get(i)
                                    : null;
                                if (v == null || v.isEmpty()) {
                                    ps.setNull(i + 1, Types.VARCHAR);
                                } else {
                                    ps.setObject(i + 1, v);
                                }
                            }
                            ps.addBatch();
                            rows++;
                            if (rows % 500 == 0) ps.executeBatch();
                        }
                        ps.executeBatch();
                    }
                    tables++;
                }
            }
            target.commit();
        } catch (Exception e) {
            try { target.rollback(); } catch (Exception ignored) {}
            throw e;
        } finally {
            if (fkOff) {
                try { enableForeignKeys(target); } catch (Exception ignored) {}
            }
            target.setAutoCommit(oldAuto);
            if (encrypted && zipFile != null) zipFile.delete();
        }

        String sha = sha256Of(backupFile);
        RestoreResult r = new RestoreResult();
        r.source = backupFile.getAbsolutePath(); r.encrypted = encrypted;
        r.tables = tables; r.rows = rows; r.sha256 = sha;
        return r;
    }

    // =========================================================================
    //  USB RESULT PACK
    // =========================================================================

    /**
     * Writes a self-contained recovery pack into {@code targetDir} (a USB
     * stick or any folder): the .klcbackup snapshot + SHA-256 checksum +
     * one-page restore manual. Everything the school needs to recover on
     * another PC.
     */
    public static UsbPackResult exportUsbPack(File targetDir, String userId)
            throws Exception {
        return exportUsbPackTo(DatabaseManager.getConnection(), userId,
            targetDir);
    }

    /** Core implementation - explicit connection + target dir (testable). */
    static UsbPackResult exportUsbPackTo(Connection conn, String userId,
            File targetDir) throws Exception {
        if (targetDir == null || !targetDir.isDirectory()
                || !targetDir.canWrite())
            throw new IllegalArgumentException(
                "Choose a writable folder/USB drive first.");
        UsbPackResult res = new UsbPackResult();
        res.dir = targetDir.getAbsolutePath();

        String stamp = LocalDateTime.now().format(
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        File pack = new File(targetDir,
            "KLC_restore_pack_" + stamp + ".klcbackup");
        BackupResult b = createBackupTo(conn, userId, pack);
        res.files.add(pack.getName());

        String sha = sha256Of(pack);
        File chk = new File(targetDir, "KLC_checksum_" + stamp + ".sha256");
        Files.write(chk.toPath(),
            (sha + "  " + pack.getName() + "\n")
                .getBytes(StandardCharsets.UTF_8));
        res.files.add(chk.getName());

        File readme = new File(targetDir, "KLC_RESTORE_MANUAL.txt");
        String manual =
            "KNOWLEDGE LAND COLLEGE CBT - RESTORE PACK\n"
            + "========================================\n"
            + "This USB pack contains a full database snapshot taken at "
            + stamp + ".\n\n"
            + "TO RESTORE ON ANY PC\n"
            + "1. Install the Knowledge Land CBT application on the target "
            + "PC.\n"
            + "2. Start the app once (it creates an empty local cache), "
            + "log in as an admin.\n"
            + "3. Open: Admin -> Backup and Recovery -> Restore from "
            + "Backup.\n"
            + "4. Choose: " + pack.getName() + "\n"
            + "5. Confirm. The app reloads every table inside one "
            + "transaction.\n"
            + "   If anything fails, nothing is changed (full rollback).\n\n"
            + "VERIFY INTEGRITY\n"
            + "SHA-256 (" + chk.getName() + "):\n" + sha + "\n\n"
            + "Encrypted packs (KLCENC1 header) require the same backup.key "
            + "that was configured when the pack was created.\n";
        Files.write(readme.toPath(), manual.getBytes(StandardCharsets.UTF_8));
        res.files.add(readme.getName());
        return res;
    }

    // =========================================================================
    //  HELPERS
    // =========================================================================

    private static void logBackup(String userId, String type, File f,
            long size, String sha, Connection conn) {
        try (Connection c = conn != null ? conn
                : DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO backup_logs(backup_type, file_path, "
                 + "file_size, checksum, created_by) "
                 + "VALUES(?,?,?,?,?)")) {
            ps.setString(1, type);
            ps.setString(2, f.getAbsolutePath());
            ps.setLong(3, size);
            ps.setString(4, sha);
            AuthService.setUuid(ps, 5, userId, c);
            ps.executeUpdate();
        } catch (Exception ignored) {}
    }

    /** All application tables, in a stable dependency-friendly order. */
    private static List<String> orderedTables(Connection c) throws Exception {
        // Preferred dump order (parents before children where it matters).
        List<String> preferred = List.of(
            "users", "student_profiles", "parent_profiles",
            "school_profile", "school_classes", "subjects",
            "teacher_subjects", "formula_sheets", "questions",
            "question_options", "exams", "exam_questions",
            "exam_attempts", "attempt_answers", "ca_scores",
            "fees_ledger", "result_pins", "results", "audit_logs",
            "announcements", "study_materials", "messages",
            "notification_queue", "user_profiles", "result_appeals");
        Set<String> names = new LinkedHashSet<>(preferred);
        try (ResultSet rs = c.getMetaData().getTables(null, null, "%",
                new String[]{"TABLE"})) {
            while (rs.next()) names.add(rs.getString(3));
        }
        names.removeAll(SKIP_TABLES);
        // keep known application tables first, any extras after
        List<String> out = new ArrayList<>();
        for (String p : preferred)
            if (names.contains(p)) { out.add(p); names.remove(p); }
        out.addAll(names);
        return out;
    }

    private static Set<String> tableColumns(Connection c, String tbl) {
        Set<String> cols = new HashSet<>();
        try (ResultSet rs = c.getMetaData().getColumns(null, null, tbl, "%")) {
            while (rs.next()) cols.add(rs.getString(4));
        } catch (Exception e) {
            return new HashSet<>();
        }
        return cols;
    }

    /** Suspends FK enforcement for the whole session (transaction-scoped). */
    private static boolean disableForeignKeys(Connection c) {
        try {
            if (c.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("h2")) {
                try (Statement st = c.createStatement()) {
                    st.execute("SET REFERENTIAL_INTEGRITY FALSE");
                }
            } else {
                try (ResultSet rs = c.getMetaData().getTables(null, null,
                        "%", new String[]{"TABLE"})) {
                    while (rs.next()) {
                        String t = rs.getString(3);
                        try (Statement st = c.createStatement()) {
                            st.execute("ALTER TABLE " + quote(t)
                                + " DISABLE TRIGGER ALL");
                        } catch (Exception ignored) {}
                    }
                }
            }
            return true;
        } catch (Exception e) {
            return false; // constraints stay on; restore may still succeed
        }
    }

    private static void enableForeignKeys(Connection c) {
        try {
            if (c.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("h2")) {
                try (Statement st = c.createStatement()) {
                    st.execute("SET REFERENTIAL_INTEGRITY TRUE");
                }
            } else {
                try (ResultSet rs = c.getMetaData().getTables(null, null,
                        "%", new String[]{"TABLE"})) {
                    while (rs.next()) {
                        String t = rs.getString(3);
                        try (Statement st = c.createStatement()) {
                            st.execute("ALTER TABLE " + quote(t)
                                + " ENABLE TRIGGER ALL");
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private static String quote(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    /**
     * Reads one CSV record (a logical line - quoted fields may contain
     * newlines) from a pushback stream. Returns the parsed fields, or null
     * at EOF before any content. Handles doubled quotes and separators
     * inside quoted fields.
     */
    private static List<String> readCsvRecord(
            java.io.PushbackInputStream in) throws Exception {
        StringBuilder cur = new StringBuilder();
        List<String> fields = new ArrayList<>();
        int ch = in.read();
        if (ch == -1) return null; // clean EOF
        boolean inQ = false;
        while (true) {
            if (ch == -1) break; // EOF inside/after content: record ends
            if (ch == '"') {
                int nx = in.read();
                if (nx == '"') {
                    cur.append('"');        // escaped quote
                } else {
                    inQ = !inQ;             // toggle quoting
                    if (nx != -1) in.unread(nx);
                }
            } else if (!inQ && ch == '\n') {
                break;
            } else if (!inQ && ch == '\r') {
                int nx = in.read();
                if (nx != -1 && nx != '\n') in.unread(nx);
                break;
            } else if (!inQ && ch == ',') {
                fields.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append((char) ch);
            }
            ch = in.read();
        }
        fields.add(cur.toString());
        return fields;
    }
}

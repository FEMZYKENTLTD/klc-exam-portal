package com.femzyk.klc.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Backup/restore round-trip tests (directive F6: backup, restore, USB
 * pack, strongest practical recovery). Uses isolated in-memory H2 source +
 * destination databases with a deliberately awkward data set: commas,
 * quotes and newlines inside values, plus NULLs, to prove CSV fidelity.
 */
class BackupServiceTest {

    @TempDir
    static File tmp;

    private static String mem(String name) {
        return "jdbc:h2:mem:" + name + ";MODE=PostgreSQL;"
            + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    }
    private static final String TRICKY =
        "He said \"hello, world\" then\nmoved to a new line";

    private static void createSchema(Connection c, String[] extra)
            throws Exception {
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE users (id VARCHAR(36) PRIMARY KEY, "
                + "email VARCHAR(200), full_name VARCHAR(150))");
            st.execute("CREATE TABLE students (id VARCHAR(36) PRIMARY KEY, "
                + "user_id VARCHAR(36), arm VARCHAR(10))");
            st.execute("CREATE TABLE notes (id VARCHAR(36) PRIMARY KEY, "
                + "txt CLOB)");
            for (String ddl : extra) st.execute(ddl);
        }
    }

    private static void seed(Connection c) throws Exception {
        try (Statement st = c.createStatement()) {
            st.execute("INSERT INTO users VALUES('u1', 'a,b@school.klc', "
                + "'Ada, the first')");
            st.execute("INSERT INTO users VALUES('u2', 'plain@school.klc', "
                + "'Bob')");
            st.execute("INSERT INTO users VALUES('u3', 'x@school.klc', NULL)");
            st.execute("INSERT INTO students VALUES('s1', 'u1', 'A')");
            st.execute("INSERT INTO students VALUES('s2', 'u2', 'B')");
            st.execute("INSERT INTO notes VALUES('n1', '" + TRICKY + "')");
            st.execute("INSERT INTO notes VALUES('n2', NULL)");
        }
    }

    private static int count(Connection c, String table) throws Exception {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void backupRestoreRoundTripPreservesRowsAndValues() throws Exception {
        String srcUrl = mem("klc_src_" + System.nanoTime());
        String dstUrl = mem("klc_dst_" + System.nanoTime());
        try (Connection src = DriverManager.getConnection(srcUrl, "sa", "");
             Connection dst = DriverManager.getConnection(dstUrl, "sa", "")) {
            createSchema(src, new String[]{});
            createSchema(dst, new String[]{});
            seed(src);

            File out = new File(tmp, "roundtrip.klcbackup");
            BackupService.BackupResult b =
                BackupService.createBackupTo(src, null, out);
            assertTrue(out.exists(), "backup file must exist");
            assertTrue(b.tables >= 3, "3+ tables backed up, got " + b.tables);
            assertEquals(b.sha256, BackupServiceTest.sha(out));

            // corrupt-free plain backup (backup.key not set in test config)
            assertFalse(BackupService.isEncrypted(out));

            BackupService.RestoreResult r =
                BackupService.restoreInto(out, dst);
            assertEquals(3, r.tables, "all 3 tables restored");
            assertEquals(7, r.rows, "all 7 rows restored");

            assertEquals(3, count(dst, "users"));
            assertEquals(2, count(dst, "students"));
            assertEquals(2, count(dst, "notes"));

            try (Statement st = dst.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT email, full_name FROM users "
                     + "WHERE id = 'u1'")) {
                assertTrue(rs.next());
                assertEquals("a,b@school.klc", rs.getString(1),
                    "email containing a comma must survive");
                assertEquals("Ada, the first", rs.getString(2));
            }
            try (Statement st = dst.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT txt FROM notes WHERE id = 'n1'")) {
                assertTrue(rs.next());
                assertEquals(TRICKY, rs.getString(1),
                    "newlines + quotes + commas inside a value must survive");
            }
            try (Statement st = dst.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT txt FROM notes WHERE id = 'n2'")) {
                assertTrue(rs.next());
                assertTrue(rs.getString(1) == null,
                    "NULL cell must restore as NULL");
            }
        }
    }

    @Test
    void restoreRollsBackOnCorruptBackup() throws Exception {
        File bad = new File(tmp, "corrupt.klcbackup");
        java.nio.file.Files.write(bad.toPath(),
            "this is not a zip file at all - clearly corrupt"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try (Connection dst = DriverManager.getConnection(
                mem("klc_dst2_" + System.nanoTime()), "sa", "")) {
            createSchema(dst, new String[]{});
            seed(dst); // dst has data
            boolean threw = false;
            try {
                BackupService.restoreInto(bad, dst);
            } catch (Exception expected) {
                threw = true;
            }
            assertTrue(threw, "corrupt file must fail loudly");
            assertEquals(3, count(dst, "users"),
                "failed restore must leave existing data untouched");
        }
    }

    @Test
    void exportUsbPackWritesBackupChecksumAndManual() throws Exception {
        File dir = new File(tmp, "usb");
        assertTrue(dir.mkdirs());
        try (Connection src = DriverManager.getConnection(
                mem("klc_src_" + System.nanoTime()), "sa", "")) {
            createSchema(src, new String[]{});
            seed(src);
            BackupService.UsbPackResult pack =
                BackupService.exportUsbPackTo(src, null, dir);
            assertTrue(pack.files.size() >= 3,
                "backup + checksum + manual expected");
            assertTrue(new File(dir, "KLC_RESTORE_MANUAL.txt").exists());
            boolean sawBackup = false, sawSha = false;
            for (String f : pack.files) {
                if (f.endsWith(".klcbackup")) sawBackup = true;
                if (f.endsWith(".sha256")) sawSha = true;
            }
            assertTrue(sawBackup && sawSha,
                "usb pack must include a backup and its checksum");
        }
    }

    private static String sha(File f) throws Exception {
        java.security.MessageDigest md =
            java.security.MessageDigest.getInstance("SHA-256");
        byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
        byte[] hash = md.digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}

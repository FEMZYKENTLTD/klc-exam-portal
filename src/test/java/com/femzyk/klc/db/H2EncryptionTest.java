package com.femzyk.klc.db;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * H2 AES encryption at rest (directive §8 + directive test list):
 *   1. an encrypted cache can be created, written, closed, reopened, read,
 *      modified and restarted again;
 *   2. the on-disk file does not contain readable plaintext markers;
 *   3. the wrong file key fails safely (no plaintext fallback);
 *   4. an existing PLAINTEXT cache migrates to an encrypted cache once,
 *      preserving data, and the plaintext file is removed;
 *   5. requesting AES without a valid key fails closed - the app refuses to
 *      create a silent plaintext cache.
 *
 * NOTE: these tests repoint the shared DatabaseManager at temp files, so
 * the classpath test config (in-memory H2) is restored in @AfterAll.
 */
class H2EncryptionTest {

    private static final String FILE_KEY =
        "correct-horse-battery-staple-klc-key-1";
    private static final String MARKER = "AES_MARKER_XYZ_987654321";

    @TempDir
    static File tmp;

    @BeforeAll
    @AfterAll
    static void restoreConfig() {
        // Back to the classpath test config (in-memory H2) so the rest of
        // the suite is unaffected by this test's file-based config.
        DatabaseManager.init();
    }

    private static Properties plainProps(File dir, boolean aes) {
        Properties p = new Properties();
        p.setProperty("supabase.db.url", "");
        p.setProperty("supabase.db.pooler.url", "");
        p.setProperty("h2.url", "jdbc:h2:file:" + dir.getAbsolutePath()
            + "/klc;CASE_INSENSITIVE_IDENTIFIERS=TRUE;MODE=PostgreSQL");
        p.setProperty("h2.user", "sa");
        p.setProperty("h2.password", "");
        p.setProperty("h2.encryption", aes ? "aes" : "off");
        if (aes) p.setProperty("h2.cipher.filePassword", FILE_KEY);
        return p;
    }

    private static String plainUrl(File dir) {
        return "jdbc:h2:file:" + dir.getAbsolutePath()
            + "/klc;CASE_INSENSITIVE_IDENTIFIERS=TRUE;MODE=PostgreSQL";
    }

    private static String cipherUrl(File dir) {
        return plainUrl(dir) + ";CIPHER=AES";
    }

    private static void writeMarker(Connection c, String marker)
            throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS aes_probe "
                + "(id INT PRIMARY KEY, note VARCHAR(200))");
            s.execute("DELETE FROM aes_probe");
            s.execute("INSERT INTO aes_probe(id, note) VALUES(1, '"
                + marker + "')");
        }
    }

    private static String readMarker(Connection c) throws Exception {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT note FROM aes_probe")) {
            assertTrue(rs.next(), "expected a probe row");
            return rs.getString(1);
        }
    }

    @Test
    void encryptedCacheRoundTripAndRestart() throws Exception {
        File dir = new File(tmp, "roundtrip");
        dir.mkdirs();
        Properties aes = plainProps(dir, true);
        DatabaseManager.applyConfig(aes);
        DatabaseInitializer.initialize();

        try (Connection c = DatabaseManager.getCacheConnection()) {
            writeMarker(c, MARKER);
        }

        // "close application / reopen application": re-apply the same config,
        // which re-opens the AES file and must read the same data.
        DatabaseManager.applyConfig(aes);
        try (Connection c = DatabaseManager.getCacheConnection()) {
            assertTrue(readMarker(c).equals(MARKER),
                "reopened encrypted cache must preserve data");
            // modify
            try (Statement s = c.createStatement()) {
                s.executeUpdate("UPDATE aes_probe SET note='"
                    + MARKER + "_UPDATED' WHERE id=1");
            }
        }

        // restart again -> modification persisted
        DatabaseManager.applyConfig(aes);
        try (Connection c = DatabaseManager.getCacheConnection()) {
            assertTrue(readMarker(c).equals(MARKER + "_UPDATED"));
        }

        // at-rest check: the database file must not contain readable marker
        File dbFile = new File(dir, "klc.mv.db");
        assertTrue(dbFile.exists(), "H2 file must exist");
        String bytes = new String(Files.readAllBytes(dbFile.toPath()),
            StandardCharsets.ISO_8859_1);
        assertFalse(bytes.contains(MARKER),
            "plaintext marker must not appear in the encrypted file");
    }

    @Test
    void wrongFileKeyFailsSafely() throws Exception {
        File dir = new File(tmp, "wrongkey");
        dir.mkdirs();
        DatabaseManager.applyConfig(plainProps(dir, true));
        DatabaseInitializer.initialize();
        try (Connection c = DatabaseManager.getCacheConnection()) {
            writeMarker(c, MARKER);
        }

        // wrong file password must be rejected, not silently fall back
        assertThrows(Exception.class, () -> DriverManager.getConnection(
            cipherUrl(dir), "sa", "wrong-key-entirely-123 klc"));
    }

    @Test
    void plaintextCacheMigratesToEncryptedPreservingData() throws Exception {
        File dir = new File(tmp, "migrate");
        dir.mkdirs();

        // 1) create a plaintext cache with a marker
        DatabaseManager.applyConfig(plainProps(dir, false));
        DatabaseInitializer.initialize();
        try (Connection c = DatabaseManager.getCacheConnection()) {
            writeMarker(c, MARKER);
        }
        File plainDb = new File(dir, "klc.mv.db");
        assertTrue(plainDb.exists());

        // 2) enable AES -> auto migration must preserve the marker
        DatabaseManager.applyConfig(plainProps(dir, true));
        try (Connection c = DatabaseManager.getCacheConnection()) {
            assertTrue(readMarker(c).equals(MARKER),
                "migrated encrypted cache must preserve data");
        }

        // plaintext file must be gone and the bytes must be encrypted now
        assertFalse(plainDb.exists()
                || new File(dir, "klc.mv.db.cipher").exists(),
            "plaintext H2 file must be removed after migration");
        String bytes = new String(
            Files.readAllBytes(new File(dir, "klc.mv.db").toPath()),
            StandardCharsets.ISO_8859_1);
        assertFalse(bytes.contains(MARKER),
            "marker must not remain readable after migration");
    }

    @Test
    void aesWithoutValidKeyFailsClosed() throws Exception {
        File dir = new File(tmp, "failclosed");
        dir.mkdirs();
        Properties bad = plainProps(dir, true);
        bad.setProperty("h2.cipher.filePassword", "short");
        DatabaseManager.applyConfig(bad);

        // getCacheConnection must refuse rather than silently create plaintext
        assertThrows(IllegalStateException.class,
            DatabaseManager::getCacheConnection);

        // and no plaintext file may have been created as a fallback
        assertFalse(new File(dir, "klc.mv.db").exists(),
            "no plaintext cache may be created when AES is misconfigured");
    }
}

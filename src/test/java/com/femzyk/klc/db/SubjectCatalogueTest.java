package com.femzyk.klc.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Subject-catalogue tests (directive §9/§10): after offline bootstrap the
 * database must carry the full Nigerian secondary-school catalogue with the
 * right class-level coverage - not a sparse subset - and the seed must be
 * idempotent (no duplicates when the catalogue is re-applied to an existing
 * database).
 */
class SubjectCatalogueTest {

    @BeforeAll
    static void bootstrap() {
        KlcTestDb.initialize();
    }

    private static final String[] JSS = {"JSS1", "JSS2", "JSS3"};
    private static final String[] SSS = {"SS1", "SS2", "SS3"};

    private int count(String name, String klass) throws Exception {
        try (Connection c = DatabaseManager.getConnection();
             ResultSet rs = c.createStatement().executeQuery(
                 "SELECT COUNT(*) FROM subjects "
                 + "WHERE UPPER(subject_name) = UPPER('"
                 + name.replace("'", "''") + "') AND class_level = '"
                 + klass + "' AND is_active = TRUE")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private void assertCoverage(List<String[]> expected) throws Exception {
        List<String> missing = new ArrayList<>();
        for (String[] e : expected) {
            if (count(e[0], e[1]) == 0) missing.add(e[0] + " " + e[1]);
        }
        assertTrue(missing.isEmpty(),
            "catalogue missing required subject/class rows: " + missing);
    }

    @Test
    void fullJssCatalogueCoversEveryClass() throws Exception {
        List<String[]> exp = new ArrayList<>();
        for (String k : JSS) {
            for (String s : new String[]{
                "MATHEMATICS", "ENGLISH LANGUAGE", "BASIC SCIENCE",
                "BASIC TECHNOLOGY", "DIGITAL TECHNOLOGY", "SOCIAL STUDIES",
                "CIVIC EDUCATION", "AGRICULTURAL SCIENCE",
                "BUSINESS STUDIES", "HOME ECONOMICS",
                "CULTURAL AND CREATIVE ART", "FRENCH",
                "CHRISTIAN RELIGIOUS STUDIES", "ISLAMIC RELIGIOUS KNOWLEDGE"})
                exp.add(new String[]{s, k});
        }
        assertCoverage(exp);
    }

    @Test
    void fullSssCatalogueCoversEveryClass() throws Exception {
        List<String[]> exp = new ArrayList<>();
        for (String k : SSS) {
            for (String s : new String[]{
                "MATHEMATICS", "ENGLISH LANGUAGE", "PHYSICS", "CHEMISTRY",
                "BIOLOGY", "ECONOMICS", "GOVERNMENT",
                "LITERATURE IN ENGLISH", "GEOGRAPHY", "AGRICULTURAL SCIENCE",
                "COMMERCE", "ACCOUNTING", "DATA PROCESSING",
                "CIVIC EDUCATION", "FURTHER MATHEMATICS"})
                exp.add(new String[]{s, k});
        }
        assertCoverage(exp);
    }

    @Test
    void catalogueRowCountMatchesAuthoritativeSeed() throws Exception {
        // 19 subjects per JSS level x 3 + 26 subjects per SS level x 3 = 135
        try (Connection c = DatabaseManager.getConnection();
             ResultSet rs = c.createStatement().executeQuery(
                 "SELECT COUNT(*) FROM subjects WHERE is_active = TRUE")) {
            rs.next();
            assertTrue(rs.getInt(1) >= 135,
                "expected >= 135 catalogue rows, got " + rs.getInt(1));
        }
    }

    @Test
    void reApplyingCatalogueCreatesNoDuplicates() throws Exception {
        // Simulate an upgrade run: seedSubjects is idempotent per code and is
        // invoked on every startup, so re-running its body must not duplicate.
        int before;
        try (Connection c = DatabaseManager.getConnection();
             ResultSet rs = c.createStatement().executeQuery(
                 "SELECT COUNT(*) FROM subjects")) {
            rs.next();
            before = rs.getInt(1);
        }

        java.lang.reflect.Method seed = DatabaseInitializer.class
            .getDeclaredMethod("seedSubjects", Connection.class);
        seed.setAccessible(true);
        try (Connection c = DatabaseManager.getConnection()) {
            seed.invoke(null, c);
        }

        try (Connection c = DatabaseManager.getConnection();
             ResultSet rs = c.createStatement().executeQuery(
                 "SELECT COUNT(*) FROM subjects")) {
            rs.next();
            assertEquals(before, rs.getInt(1),
                "re-running the catalogue seed must not create duplicates");
        }
    }

    @Test
    void representativeCodesExist() throws Exception {
        // Subject-code directory spot checks (unique codes per class row)
        try (Connection c = DatabaseManager.getConnection();
             ResultSet rs = c.createStatement().executeQuery(
                 "SELECT subject_code FROM subjects WHERE subject_name = "
                 + "'GEOGRAPHY' AND class_level = 'SS1'")) {
            assertTrue(rs.next());
            assertEquals("GEO-SS1", rs.getString(1));
        }
    }
}

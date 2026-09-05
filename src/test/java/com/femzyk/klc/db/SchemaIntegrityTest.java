package com.femzyk.klc.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Independent database verification (rule 11): the full offline schema is
 * created by DatabaseInitializer against in-memory H2 (PostgreSQL mode) and
 * then exercised with controlled data - CRUD, JOINs, UNIQUE constraints,
 * foreign keys and deactivation semantics.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SchemaIntegrityTest {

    @BeforeAll
    static void bootstrap() {
        KlcTestDb.initialize();
    }

    @Test
    @Order(1)
    void fullOfflineSchemaCreated() throws Exception {
        Set<String> tables = new HashSet<>();
        try (Connection c = DatabaseManager.getConnection();
             ResultSet rs = c.getMetaData().getTables(null, null, "%",
                 new String[]{"TABLE"})) {
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME").toLowerCase());
            }
        }
        // Core tables required by every role/workflow
        for (String t : new String[]{
                "users", "student_profiles", "parent_profiles",
                "subjects", "teacher_subjects", "school_classes",
                "questions", "question_options", "exam_questions",
                "exams", "exam_attempts", "attempt_answers",
                "ca_scores", "results", "result_pins",
                "school_profile", "audit_logs", "announcements",
                "sync_queue", "backup_logs", "formula_sheets",
                "user_profiles", "friendships", "messages",
                "result_appeals", "notification_queue",
                "study_materials"}) {
            assertTrue(tables.contains(t),
                "table missing after offline bootstrap: " + t);
        }
    }

    @Test
    @Order(2)
    void controlledCrudFlow() throws Exception {
        try (Connection c = DatabaseManager.getConnection()) {
            String userId = insertUser(c, "DB-USER-1", "db1@test.klc");
            try {
                // INSERT a subject and a class, then JOIN users->teacher_subjects
                String subjectId = insertSubject(c, "TEST SUBJECT", "TST-SS1");
                c.createStatement().executeUpdate(
                    "INSERT INTO teacher_subjects(id, teacher_id, subject_id, "
                    + "class_level) VALUES('00000000-0000-0000-0000-00000000aa', '"
                    + userId + "', '" + subjectId + "', 'SS1')");

                // SELECT + JOIN across 3 tables
                try (ResultSet rs = c.createStatement().executeQuery(
                        "SELECT u.email, s.subject_name, ts.class_level "
                        + "FROM users u "
                        + "JOIN teacher_subjects ts ON ts.teacher_id = u.id "
                        + "JOIN subjects s ON s.id = ts.subject_id "
                        + "WHERE u.id = '" + userId + "'")) {
                    assertTrue(rs.next(), "join returned no row");
                    assertEquals("db1@test.klc", rs.getString(1));
                    assertEquals("TEST SUBJECT", rs.getString(2));
                    assertEquals("SS1", rs.getString(3));
                }

                // UPDATE
                int rows = c.createStatement().executeUpdate(
                    "UPDATE subjects SET is_active = FALSE WHERE id = '"
                    + subjectId + "'");
                assertEquals(1, rows);

                // DELETE (deactivation equivalent used by the app)
                rows = c.createStatement().executeUpdate(
                    "DELETE FROM teacher_subjects WHERE teacher_id = '"
                    + userId + "'");
                assertEquals(1, rows);
            } finally {
                cleanup(userId);
            }
        }
    }

    @Test
    @Order(3)
    void uniqueConstraintsEnforced() throws Exception {
        try (Connection c = DatabaseManager.getConnection()) {
            String u1 = insertUser(c, "DB-USER-2", "db2@test.klc");
            String u2 = insertUser(c, "DB-USER-3", "db3@test.klc");
            try {
                String subj = insertSubject(c, "DUP SUBJECT", "DUP-SS1");

                // student_profiles.admission_no is UNIQUE NOT NULL
                insertStudentProfile(c, u1, "ADM-2024-001", "KERIPE", "SS1");
                assertThrows(SQLException.class, () ->
                    insertStudentProfile(c, u2, "ADM-2024-001", "OTHER", "SS1"),
                    "duplicate admission_no must violate UNIQUE");

                // users.email is UNIQUE
                assertThrows(SQLException.class, () ->
                    insertUser(c, "DB-USER-4", "db2@test.klc"),
                    "duplicate email must violate UNIQUE");
            } finally {
                cleanup(u1);
                cleanup(u2);
            }
        }
    }

    @Test
    @Order(4)
    void cloudSchemaDeclaresCoreForeignKeys() throws Exception {
        // The H2 offline cache intentionally stores plain VARCHAR ids and is
        // the app's offline fallback; the CLOUD schema is the system of
        // record and must enforce referential integrity - the offline sync
        // queue replays rows into it. Verify every core FK is declared in
        // the shipped Supabase DDL (run from the repository root, matching
        // CI's migrate job).
        java.io.File base =
            new java.io.File("supabase", "klc_supabase_schema.sql");
        assertTrue(base.exists(),
            "supabase/klc_supabase_schema.sql must exist at repo root (cwd="
            + new java.io.File(".").getAbsolutePath() + ")");
        String ddl = java.nio.file.Files.readString(base.toPath())
            .toLowerCase();

        String[][] fks = {
            // child table            expected REFERENCES target
            {"student_profiles",      "references users(id)"},
            {"results",               "references exam_attempts(id)"},
            {"exam_attempts",         "references exams(id)"},
            {"exam_attempts",         "references users(id)"},
            {"attempt_answers",       "references exam_attempts(id)"},
            {"attempt_answers",       "references questions(id)"},
            {"question_options",      "references questions(id)"},
            {"exam_questions",        "references exams(id)"},
            {"exam_questions",        "references questions(id)"},
            {"teacher_subjects",      "references users(id)"},
            {"teacher_subjects",      "references subjects(id)"},
            {"ca_scores",             "references subjects(id)"},
        };
        StringBuilder missing = new StringBuilder();
        for (String[] fk : fks) {
            String needle = "create table " + fk[0] + " (";
            int t = ddl.indexOf(needle);
            assertTrue(t >= 0, "table not found in cloud schema: " + fk[0]);
            int end = ddl.indexOf("\n);", t);
            String body = end < 0
                ? ddl.substring(t) : ddl.substring(t, end);
            if (!body.contains(fk[1])) {
                missing.append(fk[0]).append(" -> ")
                       .append(fk[1]).append("\n");
            }
        }
        assertTrue(missing.length() == 0,
            "cloud schema is missing declared foreign keys:\n" + missing);
    }

    @Test
    @Order(5)
    void schoolProfileSeededSingleton() throws Exception {
        try (Connection c = DatabaseManager.getConnection();
             ResultSet rs = c.createStatement().executeQuery(
                 "SELECT COUNT(*), MIN(school_name) FROM school_profile")) {
            assertTrue(rs.next());
            assertTrue(rs.getInt(1) >= 1, "school_profile must be seeded");
            assertNotNull(rs.getString(2));
        }
    }

    @Test
    @Order(6)
    void attemptAndResultInsertedForStudent() throws Exception {
        try (Connection c = DatabaseManager.getConnection()) {
            String student = insertUser(c, "DB-STUDENT-1", "dbstu1@test.klc");
            try {
                insertStudentProfile(c, student, "ADM-STU-0001", "STUD", "SS2");
                String subject = insertSubject(c, "RESULT SUBJECT", "RES-SS2");
                String examId = "00000000-0000-0000-0000-0000000000ee";
                c.createStatement().executeUpdate(
                    "INSERT INTO exams(id, subject_id, class_level, "
                    + "title, duration_minutes) VALUES('" + examId + "', '"
                    + subject + "', 'SS2', 'RESULT TEST EXAM', 30)");
                String attemptId = "00000000-0000-0000-0000-0000000000ff";
                c.createStatement().executeUpdate(
                    "INSERT INTO exam_attempts(id, exam_id, student_id, "
                    + "admission_no, status) VALUES('" + attemptId + "', '"
                    + examId + "', '" + student
                    + "', 'ADM-STU-0001', 'SUBMITTED')");
                c.createStatement().executeUpdate(
                    "INSERT INTO results(id, attempt_id, student_id, exam_id, "
                    + "score, total_questions, correct_answers, percentage) "
                    + "VALUES('00000000-0000-0000-0000-0000000000a1', '"
                    + attemptId + "', '" + student + "', '" + examId
                    + "', 80.0, 20, 16, 80.0)");

                // JOIN attempts + results = the result-view query shape
                try (ResultSet rs = c.createStatement().executeQuery(
                        "SELECT r.percentage, r.correct_answers, e.title "
                        + "FROM results r "
                        + "JOIN exam_attempts a ON a.id = r.attempt_id "
                        + "JOIN exams e ON e.id = r.exam_id "
                        + "WHERE r.student_id = '" + student + "'")) {
                    assertTrue(rs.next());
                    assertEquals(80.0, rs.getDouble(1), 0.001);
                    assertEquals(16, rs.getInt(2));
                    assertEquals("RESULT TEST EXAM", rs.getString(3));
                    assertFalse(rs.next(), "only one result row expected");
                }
            } finally {
                cleanup(student);
            }
        }
    }

    // ---- helpers ---------------------------------------------------------

    private String insertUser(Connection c, String name, String email)
            throws SQLException {
        String id = java.util.UUID.randomUUID().toString();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users(id, full_name, email, password_hash, role, "
                + "is_active) VALUES(?,?,?,?,'TEACHER',TRUE)")) {
            ps.setString(1, id);
            ps.setString(2, name);
            ps.setString(3, email);
            ps.setString(4, "x-not-a-real-hash");
            ps.executeUpdate();
        }
        return id;
    }

    private String insertSubject(Connection c, String name, String code)
            throws SQLException {
        String id = java.util.UUID.randomUUID().toString();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO subjects(id, subject_name, subject_code, "
                + "class_level) VALUES(?,?,?, 'SS1')")) {
            ps.setString(1, id);
            ps.setString(2, name);
            ps.setString(3, code);
            ps.executeUpdate();
        }
        return id;
    }

    private void insertStudentProfile(Connection c, String userId,
            String admNo, String surname, String klass) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO student_profiles(id, user_id, admission_no, "
                + "surname, class_level, session, result_pin) "
                + "VALUES(?,?,?,?,?, '2024/2025', ?)")) {
            ps.setString(1, java.util.UUID.randomUUID().toString());
            ps.setString(2, userId);
            ps.setString(3, admNo);
            ps.setString(4, surname);
            ps.setString(5, klass);
            ps.setString(6, surname.toUpperCase() + klass);
            ps.executeUpdate();
        }
    }

    private void cleanup(String userId) throws Exception {
        try (Connection c = DatabaseManager.getConnection();
             Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM attempt_answers WHERE attempt_id IN "
                + "(SELECT id FROM exam_attempts WHERE student_id = '"
                + userId + "')");
            s.executeUpdate("DELETE FROM results WHERE student_id = '"
                + userId + "'");
            s.executeUpdate("DELETE FROM exam_attempts WHERE student_id = '"
                + userId + "'");
            s.executeUpdate("DELETE FROM teacher_subjects WHERE teacher_id = '"
                + userId + "'");
            s.executeUpdate("DELETE FROM student_profiles WHERE user_id = '"
                + userId + "'");
            s.executeUpdate("DELETE FROM parent_profiles WHERE user_id = '"
                + userId + "'");
            s.executeUpdate("DELETE FROM users WHERE id = '" + userId + "'");
        }
    }
}

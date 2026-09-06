package com.femzyk.klc.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared in-memory H2 test database bootstrap.
 *
 * <p>The main-code DatabaseManager + DatabaseInitializer run against the
 * TEST config.properties (in-memory H2 in PostgreSQL compatibility mode),
 * exactly as a lab PC would run them offline. Initialization runs once per
 * JVM; individual tests clean up only the rows they create.
 */
public final class KlcTestDb {

    private static volatile boolean initialized = false;

    private KlcTestDb() {}

    /** One-time, idempotent bootstrap of the full offline schema. */
    public static synchronized void initialize() {
        if (initialized) return;
        DatabaseManager.init();
        DatabaseInitializer.initialize();
        initialized = true;
    }

    /** Reset per-test rows (emails / admissions / pins under the given prefix). */
    public static void cleanTestRows(String emailPrefix) throws Exception {
        try (Connection c = DatabaseManager.getConnection()) {
            List<String> userIds = new ArrayList<>();
            try (Statement s = c.createStatement()) {
                try (ResultSet rs = s.executeQuery(
                        "SELECT id FROM users WHERE LOWER(email) LIKE '"
                        + esc(emailPrefix) + "%'")) {
                    while (rs.next()) userIds.add(rs.getString(1));
                }
            }
            try (Statement s = c.createStatement()) {
                for (String uid : userIds) {
                    s.executeUpdate("DELETE FROM attempt_answers "
                        + "WHERE attempt_id IN (SELECT id FROM exam_attempts "
                        + "WHERE student_id = '" + esc(uid) + "')");
                    s.executeUpdate("DELETE FROM exam_attempts "
                        + "WHERE student_id = '" + esc(uid) + "'");
                    s.executeUpdate("DELETE FROM results "
                        + "WHERE student_id = '" + esc(uid) + "'");
                    s.executeUpdate("DELETE FROM student_profiles "
                        + "WHERE user_id = '" + esc(uid) + "'");
                    s.executeUpdate("DELETE FROM parent_profiles "
                        + "WHERE user_id = '" + esc(uid) + "'");
                    s.executeUpdate("DELETE FROM teacher_subjects "
                        + "WHERE teacher_id = '" + esc(uid) + "'");
                    s.executeUpdate("DELETE FROM users WHERE id = '"
                        + esc(uid) + "'");
                }
            }
        }
    }

    private static String esc(String s) {
        return s.replace("'", "''");
    }
}

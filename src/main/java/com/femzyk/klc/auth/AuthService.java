package com.femzyk.klc.auth;

import at.favre.lib.crypto.bcrypt.BCrypt;
import com.femzyk.klc.db.DatabaseManager;

import java.io.InputStream;
import java.sql.*;
import java.util.Properties;
import java.util.UUID;

/**
 * Authentication Service - KLC CBT Suite v6.3
 * Fixed: UUID type casting for PostgreSQL compatibility
 */
public class AuthService {

    public static class Session {
        public static String userId, fullName, role, email;
        public static long lastActivity = System.currentTimeMillis();

        public static void clear() {
            userId = fullName = role = email = null;
            lastActivity = 0;
        }

        public static void touch() {
            lastActivity = System.currentTimeMillis();
        }
    }

    private static String codeSuperAdmin;
    private static String codeAdmin;
    private static String codeStudent;

    static {
        try {
            Properties p = new Properties();
            try (InputStream in = AuthService.class
                    .getResourceAsStream("/config.properties")) {
                if (in != null) p.load(in);
            }
            codeSuperAdmin = p.getProperty("code.super_admin",
                "FEMZYK ENTERPRISES LTD");
            codeAdmin      = p.getProperty("code.admin",   "FEMZYK");
            codeStudent    = p.getProperty("code.student",  "FEMZYKENTLTD");
        } catch (Exception e) {
            codeSuperAdmin = "FEMZYK ENTERPRISES LTD";
            codeAdmin      = "FEMZYK";
            codeStudent    = "FEMZYKENTLTD";
        }
    }

    // =========================================================================
    //  LOGIN
    //  FIX: Removed UUID comparison from WHERE clause
    //  Login uses email (VARCHAR) only - no UUID needed in query
    // =========================================================================
    public static boolean login(String email, String password) {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT id, full_name, password_hash, role, email, " +
                 "failed_login_attempts, locked_until " +
                 "FROM users " +
                 "WHERE LOWER(email) = LOWER(?) AND is_active = TRUE")) {

            ps.setString(1, email.trim());
            ResultSet rs = ps.executeQuery();

            if (rs.next()) {
                Timestamp locked = rs.getTimestamp("locked_until");
                if (locked != null &&
                        locked.getTime() > System.currentTimeMillis()) {
                    return false;
                }

                String hash = rs.getString("password_hash");
                boolean ok  = false;
                try {
                    ok = BCrypt.verifyer()
                               .verify(password.toCharArray(), hash)
                               .verified;
                } catch (Exception ignored) {}

                String uid = rs.getString("id");

                if (ok) {
                    // FIX: Use setUuid() helper for cross-DB UUID compatibility
                    try (PreparedStatement up = c.prepareStatement(
                            "UPDATE users SET failed_login_attempts=0, " +
                            "locked_until=NULL WHERE id=?")) {
                        setUuid(up, 1, uid, c);
                        up.executeUpdate();
                    }
                    Session.userId   = uid;
                    Session.fullName = rs.getString("full_name");
                    Session.role     = rs.getString("role");
                    Session.email    = rs.getString("email");
                    Session.touch();
                    logAudit("LOGIN", "users", uid);
                    return true;

                } else {
                    int fails = rs.getInt("failed_login_attempts") + 1;
                    Timestamp lockUntil = (fails >= 5)
                        ? new Timestamp(
                            System.currentTimeMillis() + 15 * 60 * 1000L)
                        : null;
                    try (PreparedStatement up = c.prepareStatement(
                            "UPDATE users SET failed_login_attempts=?, " +
                            "locked_until=? WHERE id=?")) {
                        up.setInt(1, fails);
                        up.setTimestamp(2, lockUntil);
                        setUuid(up, 3, uid, c);
                        up.executeUpdate();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    // =========================================================================
    //  REGISTER
    //  FIX: All UUID columns use setUuid() helper
    //       Works on both H2 (VARCHAR) and PostgreSQL (UUID type)
    // =========================================================================
    public static String register(
            String fullName, String email, String password, String role,
            String regCode, String admissionNo, String classLevel, String arm,
            String surname, String[] subjectIds,
            String securityQuestion, String securityAnswer) {

        // Code gate
        if ("SUPER_ADMIN".equals(role)) {
            if (!codeSuperAdmin.equals(regCode))
                return "Invalid Super Admin code.";
        } else if ("TEACHER".equals(role)
                || "EXAM_OFFICER".equals(role)
                || "PRINCIPAL_ADMIN".equals(role)) {
            if (!codeAdmin.equals(regCode))
                return "Invalid staff code. Contact the administrator.";
        } else if ("STUDENT".equals(role)) {
            if (!codeStudent.equals(
                    regCode != null ? regCode.trim() : ""))
                return "Invalid student registration code. " +
                       "Contact your school for the correct code.";
        }

        if (fullName == null || fullName.isBlank())
            return "Full name is required.";
        if (email == null || !email.contains("@"))
            return "Valid email is required.";
        if (password == null || password.length() < 6)
            return "Password must be at least 6 characters.";

        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);

            boolean isH2 = isH2(c);
            String userId = UUID.randomUUID().toString();
            String hash   = BCrypt.withDefaults()
                                  .hashToString(12, password.toCharArray());

            String secAnswerHash = null;
            if (securityQuestion != null && !securityQuestion.isBlank()
                    && securityAnswer != null && !securityAnswer.isBlank()) {
                secAnswerHash = BCrypt.withDefaults().hashToString(
                    12, securityAnswer.toLowerCase().trim().toCharArray());
            }

            // ── Insert user ────────────────────────────────────────────────
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO users(id, full_name, email, password_hash, " +
                    "role, security_question, security_answer_hash, " +
                    "password_changed_at) " +
                    "VALUES(?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)")) {
                setUuid(ps, 1, userId, c);
                ps.setString(2, fullName.trim());
                ps.setString(3, email.toLowerCase().trim());
                ps.setString(4, hash);
                ps.setString(5, role);
                ps.setString(6, securityQuestion);
                ps.setString(7, secAnswerHash);
                ps.executeUpdate();
            }

            // ── Student profile ────────────────────────────────────────────
            if ("STUDENT".equals(role)) {
                String safeSurname = (surname == null || surname.isBlank())
                    ? "KLC" : surname.trim();
                String safeClass   = (classLevel == null || classLevel.isBlank())
                    ? "" : classLevel.trim();
                String pin = safeSurname.toUpperCase()
                                        .replaceAll("\\s+", "") + safeClass;

                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO student_profiles(" +
                        "  id, user_id, admission_no, surname, other_names, " +
                        "  class_level, arm, session, result_pin) " +
                        "VALUES(?, ?, ?, ?, ?, ?, ?, '2024/2025', ?)")) {
                    setUuid(ps, 1, UUID.randomUUID().toString(), c);
                    setUuid(ps, 2, userId, c);
                    ps.setString(3, admissionNo);
                    ps.setString(4, safeSurname);
                    ps.setString(5, fullName.replaceFirst(
                        "(?i)" +
                        java.util.regex.Pattern.quote(safeSurname) +
                        "\\s*", "").trim());
                    ps.setString(6, safeClass);
                    ps.setString(7, arm == null ? "" : arm.trim());
                    ps.setString(8, pin);
                    ps.executeUpdate();
                }
            }

            // ── Teacher subjects ───────────────────────────────────────────
            if (subjectIds != null && subjectIds.length > 0) {
                for (String sid : subjectIds) {
                    if (sid == null || sid.isBlank()) continue;
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO teacher_subjects(" +
                            "  id, teacher_id, subject_id, class_level) " +
                            "VALUES(?, ?, ?, NULL)")) {
                        setUuid(ps, 1, UUID.randomUUID().toString(), c);
                        setUuid(ps, 2, userId, c);
                        setUuid(ps, 3, sid, c);
                        ps.executeUpdate();
                    }
                }
            }

            c.commit();
            logAudit("REGISTER_" + role, "users", userId);
            return "OK:" + userId;

        } catch (Exception e) {
            e.printStackTrace();
            return "Error: " + e.getMessage();
        }
    }

    // Backward-compatible overload
    public static String register(
            String fullName, String email, String password, String role,
            String regCode, String admissionNo, String classLevel, String arm,
            String surname, String[] subjectIds) {
        return register(fullName, email, password, role, regCode,
                admissionNo, classLevel, arm, surname, subjectIds,
                null, null);
    }

    // =========================================================================
    //  AUDIT LOG
    // =========================================================================
    public static void logAudit(String action, String entityType,
                                String entityId) {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO audit_logs(" +
                 "  id, user_id, action, entity_type, entity_id) " +
                 "VALUES(?, ?, ?, ?, ?)")) {
            setUuid(ps, 1, UUID.randomUUID().toString(), c);
            if (Session.userId != null)
                setUuid(ps, 2, Session.userId, c);
            else
                ps.setNull(2, Types.OTHER);
            ps.setString(3, action);
            ps.setString(4, entityType);
            if (entityId != null)
                setUuid(ps, 5, entityId, c);
            else
                ps.setNull(5, Types.OTHER);
            ps.executeUpdate();
        } catch (Exception ignored) {}
    }

    // =========================================================================
    //  UUID HELPER
    //  H2:         stores UUID as VARCHAR(36) → use setString()
    //  PostgreSQL: stores UUID as UUID type   → use setObject() with UUID class
    //  This method detects which DB is in use and sets correctly
    // =========================================================================
    public static void setUuid(PreparedStatement ps, int paramIndex,
                               String uuidStr, Connection conn)
            throws SQLException {
        if (uuidStr == null) {
            ps.setNull(paramIndex, Types.OTHER);
            return;
        }
        try {
            if (isH2(conn)) {
                // H2: VARCHAR(36) - plain string works
                ps.setString(paramIndex, uuidStr);
            } else {
                // PostgreSQL: UUID type - must use UUID object
                ps.setObject(paramIndex, UUID.fromString(uuidStr));
            }
        } catch (Exception e) {
            // Fallback: try as string
            ps.setString(paramIndex, uuidStr);
        }
    }

    // =========================================================================
    //  DETECT DATABASE TYPE
    // =========================================================================
    public static boolean isH2(Connection conn) {
        try {
            return conn.getMetaData()
                       .getDatabaseProductName()
                       .toLowerCase()
                       .contains("h2");
        } catch (Exception e) {
            return false;
        }
    }

    // =========================================================================
    //  ROLE HELPERS
    // =========================================================================
    public static boolean isSuperAdmin() {
        return "SUPER_ADMIN".equals(Session.role);
    }

    public static boolean isTeacherOrAbove() {
        return Session.role != null && !"STUDENT".equals(Session.role);
    }
}
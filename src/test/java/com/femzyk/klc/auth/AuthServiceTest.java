package com.femzyk.klc.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.femzyk.klc.db.DatabaseManager;
import com.femzyk.klc.db.KlcTestDb;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Authentication & registration business-rule tests (rules 9, 10, 13):
 * deterministic fixtures, valid + invalid cases, code gates, password
 * policy, PIN generation and collision handling, lockout behaviour.
 */
class AuthServiceTest {

    private static final String SUPER_CODE = "TEST-SUPERADMIN-CODE";
    private static final String STAFF_CODE = "TEST-STAFF-CODE";
    private static final String FAMILY_CODE = "TEST-FAMILY-CODE";

    private static int seq = 0;

    @BeforeAll
    static void bootstrap() {
        KlcTestDb.initialize();
    }

    @BeforeEach
    @AfterEach
    void resetSession() {
        AuthService.Session.clear();
        try {
            KlcTestDb.cleanTestRows("auth-test-");
        } catch (Exception ignored) {}
    }

    private String email() {
        return "auth-test-" + (seq++) + "-" + UUID.randomUUID()
            .toString().substring(0, 8) + "@test.klc";
    }

    // ---- registration gates ----------------------------------------------

    @Test
    void superAdminWrongCodeRejected() {
        String r = AuthService.register("Test Super", email(), "Str0ngPass1",
            "SUPER_ADMIN", "WRONG", null, null, null, null, null);
        assertTrue(r.startsWith("Invalid Super Admin code"), r);
    }

    @Test
    void studentWrongFamilyCodeRejected() {
        String r = AuthService.register("Test Student", email(), "student1",
            "STUDENT", "WRONG", "ADM-A-001", "SS1", "A", "STUDENT", null);
        assertTrue(r.startsWith("Invalid registration code"), r);
    }

    @Test
    void staffWrongCodeRejected() {
        String r = AuthService.register("Test Teacher", email(), "Str0ngPass1",
            "TEACHER", "WRONG", null, null, null, null, null);
        assertTrue(r.startsWith("Invalid staff code"), r);
    }

    @Test
    void staffPasswordComplexityEnforced() {
        String addr = email();
        String r1 = AuthService.register("Test Teacher", addr, "short1A",
            "TEACHER", STAFF_CODE, null, null, null, null, null);
        assertTrue(r1.contains("at least 8"), r1);          // too short

        String r2 = AuthService.register("Test Teacher", addr, "alllowercase1",
            "TEACHER", STAFF_CODE, null, null, null, null, null);
        assertTrue(r2.contains("UPPERCASE"), r2);           // no upper

        String r3 = AuthService.register("Test Teacher", addr, "NoDigitsHere",
            "TEACHER", STAFF_CODE, null, null, null, null, null);
        assertTrue(r3.contains("number"), r3);              // no digit
    }

    @Test
    void parentUsesFamilyCodeAndLinksWard() {
        // ward must exist first
        String wardEmail = email();
        String ward = AuthService.register("Ward Student", wardEmail,
            "wardpass1", "STUDENT", FAMILY_CODE, "ADM-W-011", "SS1", "A",
            "WARD", null);
        assertTrue(ward.startsWith("OK:"), ward);

        String r = AuthService.register("Mrs Parent", email(), "parent1",
            "PARENT", FAMILY_CODE, "ADM-W-011", null, null, "PARENT", null);
        assertTrue(r.startsWith("OK:"), r);

        try (Connection c = DatabaseManager.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT ward_admission_no FROM parent_profiles "
                 + "WHERE user_id = '" + r.substring(3) + "'")) {
            assertTrue(rs.next());
            assertEquals("ADM-W-011", rs.getString(1));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- successful registration + login ---------------------------------

    @Test
    void superAdminRegisterAndLogin() {
        String pw = "Str0ngPass1";
        String addr = email();
        String r = AuthService.register("Test Super Admin", addr, pw,
            "SUPER_ADMIN", SUPER_CODE, null, null, null, null, null);
        assertTrue(r.startsWith("OK:"), r);

        assertTrue(AuthService.login(addr, pw), "valid login must succeed");
        assertEquals("SUPER_ADMIN", AuthService.Session.role);
        assertEquals(addr, AuthService.Session.email);
    }

    @Test
    void studentRegisterCreatesProfileWithPin() {
        String r = AuthService.register("Chidi Okafor", email(), "student1",
            "STUDENT", FAMILY_CODE, "KLC-2024-0112", "SS1", "A",
            "Okafor", null);
        assertTrue(r.startsWith("OK:"), r);

        try (Connection c = DatabaseManager.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT admission_no, result_pin, class_level "
                 + "FROM student_profiles WHERE user_id = '"
                 + r.substring(3) + "'")) {
            assertTrue(rs.next());
            assertEquals("KLC-2024-0112", rs.getString(1));
            assertEquals("OKAFORSS1", rs.getString(2)); // SURNAME+CLASS
            assertEquals("SS1", rs.getString(3));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void duplicateStudentEmailRejected() {
        String addr = email();
        String r1 = AuthService.register("First Student", addr, "student1",
            "STUDENT", FAMILY_CODE, "KLC-ADM-101", "SS1", "A", "FIRST", null);
        assertTrue(r1.startsWith("OK:"), r1);

        String r2 = AuthService.register("Second Student", addr, "student1",
            "STUDENT", FAMILY_CODE, "KLC-ADM-202", "SS1", "A", "SECOND", null);
        assertTrue(r2.startsWith("Error:"),
            "duplicate email must fail: " + r2);
    }

    @Test
    void duplicateAdmissionNumberRejected() {
        String r1 = AuthService.register("Student One", email(), "student1",
            "STUDENT", FAMILY_CODE, "KLC-SAME-001", "SS1", "A", "ONE", null);
        assertTrue(r1.startsWith("OK:"), r1);

        String r2 = AuthService.register("Student Two", email(), "student1",
            "STUDENT", FAMILY_CODE, "KLC-SAME-001", "SS1", "A", "TWO", null);
        assertTrue(r2.startsWith("Error:"),
            "duplicate admission number must fail: " + r2);
    }

    @Test
    void resultPinCollisionGetsNumericSuffix() {
        // same surname + class => second student's PIN must gain the last 3
        // digits of the admission number (KERIPESS2 -> KERIPESS2234)
        String r1 = AuthService.register("A KERIPE", email(), "student1",
            "STUDENT", FAMILY_CODE, "KLC-2024-0001", "SS2", "A",
            "KERIPE", null);
        assertTrue(r1.startsWith("OK:"), r1);

        String r2 = AuthService.register("B KERIPE", email(), "student1",
            "STUDENT", FAMILY_CODE, "KLC-2024-0234", "SS2", "A",
            "KERIPE", null);
        assertTrue(r2.startsWith("OK:"), r2);

        try (Connection c = DatabaseManager.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT result_pin FROM student_profiles "
                 + "WHERE user_id = '" + r2.substring(3) + "'")) {
            assertTrue(rs.next());
            assertEquals("KERIPESS2234", rs.getString(1));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- login failure handling ------------------------------------------

    @Test
    void invalidPasswordRejectedAndNoSession() {
        String addr = email();
        assertTrue(AuthService.register("Login Student", addr, "student1",
            "STUDENT", FAMILY_CODE, "KLC-LOG-001", "SS1", "A", "LOG", null)
            .startsWith("OK:"));
        AuthService.Session.clear();

        assertFalse(AuthService.login(addr, "wrong-password"));
        assertTrue(AuthService.Session.userId == null,
            "no session may be set on failed login");
    }

    @Test
    void fiveFailuresLockAccountForFifteenMinutes() {
        String addr = email();
        String pw = "student1";
        assertTrue(AuthService.register("Lock Student", addr, pw,
            "STUDENT", FAMILY_CODE, "KLC-LCK-001", "SS1", "A", "LCK", null)
            .startsWith("OK:"));

        for (int i = 0; i < 5; i++) {
            assertFalse(AuthService.login(addr, "bad-pass-" + i));
        }

        // 6th attempt with the CORRECT password must still be refused
        assertFalse(AuthService.login(addr, pw),
            "locked account must reject even the correct password");

        try (Connection c = DatabaseManager.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT failed_login_attempts, locked_until "
                 + "FROM users WHERE LOWER(email) = LOWER('" + addr + "')")) {
            assertTrue(rs.next());
            assertEquals(5, rs.getInt(1));
            assertNotNull(rs.getTimestamp(2));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void disabledAccountCannotLogin() throws Exception {
        String addr = email();
        assertTrue(AuthService.register("Gone Student", addr, "student1",
            "STUDENT", FAMILY_CODE, "KLC-GON-001", "SS1", "A", "GON", null)
            .startsWith("OK:"));

        try (Connection c = DatabaseManager.getConnection();
             Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE users SET is_active = FALSE "
                + "WHERE LOWER(email) = LOWER('" + addr + "')");
        }
        assertFalse(AuthService.login(addr, "student1"),
            "deactivated account must not log in");
    }
}

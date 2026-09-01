package com.femzyk.klc.db;

import at.favre.lib.crypto.bcrypt.BCrypt;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

/**
 * DatabaseInitializer v1.0
 *
 * CHANGES IN THIS REVISION (everything else preserved exactly):
 * 1. H2 announcements table now includes status VARCHAR(20) DEFAULT
 *    'APPROVED' (teacher announcement approval workflow, Priority 3 #13).
 * 2. H2 users table now includes phone_type VARCHAR(20) and
 *    phone_contact VARCHAR(20) (Priority 2 #10).
 * 3. NEW ensureH2Columns(): because all H2 tables are created with
 *    IF NOT EXISTS, an EXISTING klc_cache database would never receive
 *    the new columns from CREATE alone. This method runs
 *    ALTER TABLE ... ADD COLUMN IF NOT EXISTS on every startup so old
 *    offline caches upgrade themselves in place - no data loss, no need
 *    to delete klc_cache.
 * 4. ensurePostgresColumns() now also mirrors the same three columns on
 *    Supabase (harmless if the SQL migration already added them).
 */
public class DatabaseInitializer {

    private static final String SA_NAME  = "OLUFEMI BENUA KERIPE";
    private static final String SA_EMAIL = "femzykenterprisesltd@gmail.com";
    private static final String SA_PHONE = "+2349049903679";

    // KLC v1.0 SECURITY FIX: the super-admin seed password is NO LONGER
    // hardcoded in source (the historical value + hash were published in
    // this repo and must be considered compromised). The seed password now
    // comes from config.properties (app.superadmin.password); when absent a
    // RANDOM password is generated and printed ONCE to the local console -
    // standard first-boot bootstrap. Rotate immediately after first login.
    private static String saPassword = null;

    private static synchronized String seedPassword() {
        if (saPassword != null) return saPassword;
        java.util.Properties p = new java.util.Properties();
        try (java.io.InputStream in = DatabaseInitializer.class
                .getResourceAsStream("/config.properties")) {
            if (in != null) p.load(in);
        } catch (Exception ignored) {}
        String fromCfg = p.getProperty("app.superadmin.password", "");
        saPassword = fromCfg.isBlank()
            ? "KLC-" + UUID.randomUUID().toString().substring(0, 13)
            : fromCfg.trim();
        return saPassword;
    }

    public static void initialize() {
        try (Connection conn = DatabaseManager.getConnection();
             Statement  stmt = conn.createStatement()) {

            boolean h2 = isH2(conn);
            System.out.println("[DB] Mode: " + (h2 ? "H2 offline" : "PostgreSQL cloud"));

            if (h2) {
                createH2Tables(stmt);
                ensureH2Columns(stmt);
                seedSuperAdmin(conn);
                seedSubjects(conn);
                seedSchoolProfile(conn);
            } else {
                ensurePostgresColumns(stmt);
            }

            System.out.println("[DB] Initialization complete");

        } catch (Exception e) {
            System.err.println("[DB] Initialization error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static boolean isH2(Connection conn) {
        try {
            return conn.getMetaData()
                       .getDatabaseProductName()
                       .toLowerCase()
                       .contains("h2");
        } catch (Exception e) {
            return false;
        }
    }

    private static void createH2Tables(Statement s) throws Exception {

        s.execute(
            "CREATE TABLE IF NOT EXISTS users (" +
            "  id                    VARCHAR(36)  DEFAULT RANDOM_UUID() PRIMARY KEY," +
            "  full_name             VARCHAR(150) NOT NULL," +
            "  email                 VARCHAR(150) UNIQUE," +
            "  password_hash         VARCHAR(255) NOT NULL," +
            "  role                  VARCHAR(30)  NOT NULL," +
            "  phone                 VARCHAR(30)," +
            "  phone_type            VARCHAR(20)," +
            "  phone_contact         VARCHAR(20)," +
            "  is_active             BOOLEAN      DEFAULT TRUE," +
            "  totp_enabled          BOOLEAN      DEFAULT FALSE," +
            "  totp_secret           VARCHAR(100)," +
            "  security_question     VARCHAR(255)," +
            "  security_answer_hash  VARCHAR(255)," +
            "  failed_login_attempts INT          DEFAULT 0," +
            "  locked_until          TIMESTAMP," +
            "  password_changed_at   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP," +
            "  created_at            TIMESTAMP    DEFAULT CURRENT_TIMESTAMP," +
            "  updated_at            TIMESTAMP    DEFAULT CURRENT_TIMESTAMP" +
            ")"
        );

        s.execute(
            "CREATE TABLE IF NOT EXISTS student_profiles (" +
            "  id            VARCHAR(36)  DEFAULT RANDOM_UUID() PRIMARY KEY," +
            "  user_id       VARCHAR(36)  UNIQUE," +
            "  admission_no  VARCHAR(40)  UNIQUE NOT NULL," +
            "  surname       VARCHAR(80)  NOT NULL," +
            "  other_names   VARCHAR(120)," +
            "  class_level   VARCHAR(10)  NOT NULL," +
            "  arm           VARCHAR(20)," +
            "  session       VARCHAR(15)  NOT NULL," +
            "  gender        VARCHAR(10)," +
            "  date_of_birth DATE," +
            "  parent_phone  VARCHAR(30)," +
            "  parent_email  VARCHAR(150)," +
            "  address       TEXT," +
            "  passport_url  TEXT," +
            "  result_pin    VARCHAR(40)  UNIQUE NOT NULL," +
            "  fee_status    VARCHAR(20)  DEFAULT 'PAID'," +
            "  status        VARCHAR(20)  DEFAULT 'ACTIVE'," +
            "  created_at    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP" +
            ")"
        );

        s.execute(
            "CREATE TABLE IF NOT EXISTS school_classes (" +
            "  id               VARCHAR(36) DEFAULT RANDOM_UUID() PRIMARY KEY," +
            "  class_level      VARCHAR(10) NOT NULL," +
            "  arm              VARCHAR(20)," +
            "  class_teacher_id VARCHAR(36)," +
            "  session          VARCHAR(15)," +
            "  is_active        BOOLEAN DEFAULT TRUE" +
            ")"
        );

        s.execute(
            "CREATE TABLE IF NOT EXISTS subjects (" +
            "  id           VARCHAR(36)  DEFAULT RANDOM_UUID() PRIMARY KEY," +
            "  subject_name VARCHAR(120) NOT NULL," +
            "  subject_code VARCHAR(30)  UNIQUE NOT NULL," +
            "  class_level  VARCHAR(10)," +
            "  pass_mark    INT          DEFAULT 40," +
            "  is_active    BOOLEAN      DEFAULT TRUE," +
            "  created_by   VARCHAR(36)," +
            "  created_at   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP" +
            ")"
        );

        s.execute(
            "CREATE TABLE IF NOT EXISTS teacher_subjects (" +
            "  id          VARCHAR(36) DEFAULT RANDOM_UUID() PRIMARY KEY," +
            "  teacher_id  VARCHAR(36) NOT NULL," +
            "  subject_id  VARCHAR(36) NOT NULL," +
            "  class_level VARCHAR(10)," +
            "  assigned_by VARCHAR(36)," +
            "  assigned_at TIMESTAMP   DEFAULT CURRENT_TIMESTAMP" +
            ")"
        );

        s.execute(
            "CREATE TABLE IF NOT EXISTS questions (" +
            "  id                 VARCHAR(36)  DEFAULT RANDOM_UUID() PRIMARY KEY," +
            "  subject_id         VARCHAR(36)  NOT NULL," +
            "  class_level        VARCHAR(10)," +
            "  term               VARCHAR(10)," +
            "  topic              VARCHAR(150)," +
            "  difficulty         VARCHAR(10)," +
            "  question_text      TEXT         NOT NULL," +
            "  question_image_url TEXT," +
            "  question_type      VARCHAR(20)  DEFAULT 'MCQ'," +
            "  explanation        TEXT," +
            "  source             VARCHAR(80)," +
            "  marks              INT          DEFAULT 1," +
            "  is_approved        BOOLEAN      DEFAULT FALSE," +
            "  created_by         VARCHAR(36)," +
            "  on_behalf_of       VARCHAR(36)," +
            "  created_at         TIMESTAMP    DEFAULT CURRENT_TIMESTAMP," +
            "  version            INT          DEFAULT 1" +
            ")"
        );

        s.execute(
            "CREATE TABLE IF NOT EXISTS question_options (" +
            "  id           VARCHAR(36) DEFAULT RANDOM_UUID() PRIMARY KEY," +
            "  question_id  VARCHAR(36) NOT NULL," +
            "  option_label CHAR(1)     NOT NULL," +
            "  option_text  TEXT        NOT NULL," +
            "  is_correct   BOOLEAN     DEFAULT FALSE" +
            ")"
        );

        s.execute(
            "CREATE TABLE IF NOT EXISTS exams (" +
            "  id               VARCHAR(36)  DEFAULT RANDOM_UUID() PRIMARY KEY," +
            "  subject_id       VARCHAR(36)  NOT NULL," +
            "  class_level      VARCHAR(10)  NOT NULL," +
            "  arm              VARCHAR(20)," +
            "  term             VARCHAR(10)," +
            "  session          VARCHAR(15)," +
            "  title            VARCHAR(200)," +
            "  instructions     TEXT," +
            "  duration_minutes INT          NOT NULL," +
            "  total_marks      INT," +
            "  pass_mark        INT," +
            "  start_at         TIMESTAMP," +
            "  end_at           TIMESTAMP," +
            "  attempt_limit    INT          DEFAULT 1," +
            "  is_practice      BOOLEAN      DEFAULT FALSE," +
            "  fee_gate         BOOLEAN      DEFAULT FALSE," +
            "  negative_marking NUMERIC(3,2) DEFAULT 0," +
            "  is_active        BOOLEAN      DEFAULT TRUE," +
            "  created_by       VARCHAR(36)," +
            "  created_at       TIMESTAMP    DEFAULT CURRENT_TIMESTAMP" +
            ")"
        );

        s.execute(
            "CREATE TABLE IF NOT EXISTS exam_questions (" +
            "  exam_id        VARCHAR(36) NOT NULL," +
            "  question_id    VARCHAR(36) NOT NULL," +
            "  question_order INT," +
            "  PRIMARY KEY (exam_id, question_id)" +
            ")"
        );

        s.execute(
            "CREATE TABLE IF NOT EXISTS exam_attempts (" +
            "  id              VARCHAR(36) DEFAULT RANDOM_UUID() PRIMARY KEY," +
            "  exam_id         VARCHAR(36) NOT NULL," +
            "  student_id      VARCHAR(36) NOT NULL," +
            "  admission_no    VARCHAR(40)," +
            "  variant         CHAR(1)     DEFAULT 'A'," +
            "  started_at      TIMESTAMP   DEFAULT CURRENT_TIMESTAMP," +
            "  submitted_at    TIMESTAMP," +
            "  time_remaining  INT," +
            "  strike_count    INT         DEFAULT 0," +
            "  malpractice_log TEXT," +
            "  ip_address      VARCHAR(45)," +
            "  pc_name         VARCHAR(100)," +
            "  status          VARCHAR(20) DEFAULT 'IN_PROGRESS'," +
            "  synced          BOOLEAN     DEFAULT TRUE" +
            ")"
        );

        s.execute(
            "CREATE TABLE IF NOT EXISTS attempt_answers (" +
            "  id              VARCHAR(36) DEFAULT RANDOM_UUID() PRIMARY KEY," +
            "  attempt_id      VARCHAR(36) NOT NULL," +
            "  question_id     VARCHAR(36) NOT NULL," +
            "  selected_option CHAR(1)," +
            "  is_flagged      BOOLEAN     DEFAULT FALSE," +
            "  time_spent      INT         DEFAULT 0," +
            "  answered_at     TIMESTAMP   DEFAULT CURRENT_TIMESTAMP" +
            ")"
        );

        s.execute(
            "CREATE TABLE IF NOT EXISTS ca_scores (" +
            "  id          VARCHAR(36)  DEFAULT RANDOM_UUID() PRIMARY KEY," +
            "  student_id  VARCHAR(36)," +
            "  subject_id  VARCHAR(36)," +
            "  class_level VARCHAR(10)," +
            "  term        VARCHAR(10)," +
            "  session     VARCHAR(15)," +
            "  ca1_score   NUMERIC(5,2) DEFAULT 0," +
            "  ca2_score   NUMERIC(5,2) DEFAULT 0," +
            "  exam_score  NUMERIC(5,2)," +
            "  total_score NUMERIC(5,2)," +
            "  grade       VARCHAR(5)," +
            "  remark      VARCHAR(50)," +
            "  position    INT" +
            ")"
        );

        s.execute(
            "CREATE TABLE IF NOT EXISTS results (" +
            "  id              VARCHAR(36)  DEFAULT RANDOM_UUID() PRIMARY KEY," +
            "  attempt_id      VARCHAR(36)  UNIQUE," +
            "  student_id      VARCHAR(36)," +
            "  exam_id         VARCHAR(36)," +
            "  score           NUMERIC(5,2)," +
            "  total_questions INT," +
            "  correct_answers INT," +
            "  percentage      NUMERIC(5,2)," +
            "  grade           VARCHAR(5)," +
            "  position        INT," +
            "  published       BOOLEAN      DEFAULT TRUE," +
            "  created_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP" +
            ")"
        );

        s.execute(
            "CREATE TABLE IF NOT EXISTS result_pins (" +
            "  id         VARCHAR(36) DEFAULT RANDOM_UUID() PRIMARY KEY," +
            "  student_id VARCHAR(36) UNIQUE," +
            "  pin_code   VARCHAR(40) UNIQUE NOT NULL," +
            "  is_active  BOOLEAN     DEFAULT TRUE," +
            "  created_at TIMESTAMP   DEFAULT CURRENT_TIMESTAMP" +
            ")"
        );

        s.execute(
            "CREATE TABLE IF NOT EXISTS school_profile (" +
            "  id                      VARCHAR(36)  DEFAULT RANDOM_UUID() PRIMARY KEY," +
            "  school_name             VARCHAR(200) DEFAULT 'KNOWLEDGE LAND COLLEGE'," +
            "  address                 TEXT," +
            "  motto                   VARCHAR(200)," +
            "  logo_url                TEXT," +
            "  principal_name          VARCHAR(150)," +
            "  principal_signature_url TEXT," +
            "  phone                   VARCHAR(50)," +
            "  email                   VARCHAR(150)," +
            "  grading_scale           CLOB," +
            "  session_current         VARCHAR(15)  DEFAULT '2024/2025'," +
            "  term_current            VARCHAR(10)  DEFAULT '1st'," +
            "  campus_name             VARCHAR(120)," +
            "  updated_at              TIMESTAMP    DEFAULT CURRENT_TIMESTAMP" +
            ")"
        );

        s.execute(
            "CREATE TABLE IF NOT EXISTS announcements (" +
            "  id          VARCHAR(36)  DEFAULT RANDOM_UUID() PRIMARY KEY," +
            "  title       VARCHAR(200) NOT NULL," +
            "  body        TEXT         NOT NULL," +
            "  target_role VARCHAR(30)  DEFAULT 'ALL'," +
            "  status      VARCHAR(20)  DEFAULT 'APPROVED'," +
            "  created_by  VARCHAR(36)," +
            "  created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP," +
            "  expires_at  TIMESTAMP" +
            ")"
        );

        s.execute(
            "CREATE TABLE IF NOT EXISTS study_materials (" +
            "  id          VARCHAR(36)  DEFAULT RANDOM_UUID() PRIMARY KEY," +
            "  subject_id  VARCHAR(36)," +
            "  class_level VARCHAR(10)," +
            "  title       VARCHAR(200)," +
            "  description TEXT," +
            "  file_url    TEXT," +
            "  uploaded_by VARCHAR(36)," +
            "  created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP" +
            ")"
        );

        s.execute(
            "CREATE TABLE IF NOT EXISTS fees_ledger (" +
            "  id          VARCHAR(36)  DEFAULT RANDOM_UUID() PRIMARY KEY," +
            "  student_id  VARCHAR(36)," +
            "  term        VARCHAR(10)," +
            "  session     VARCHAR(15)," +
            "  amount_due  NUMERIC(10,2)," +
            "  amount_paid NUMERIC(10,2)," +
            "  status      VARCHAR(20)," +
            "  updated_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP" +
            ")"
        );

        s.execute(
            "CREATE TABLE IF NOT EXISTS audit_logs (" +
            "  id          VARCHAR(36)  DEFAULT RANDOM_UUID() PRIMARY KEY," +
            "  user_id     VARCHAR(36)," +
            "  action      VARCHAR(120) NOT NULL," +
            "  entity_type VARCHAR(60)," +
            "  entity_id   VARCHAR(36)," +
            "  details     TEXT," +
            "  ip_address  VARCHAR(45)," +
            "  created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP" +
            ")"
        );

        s.execute(
            "CREATE TABLE IF NOT EXISTS sync_queue (" +
            "  id         VARCHAR(60)  PRIMARY KEY," +
            "  table_name VARCHAR(60)," +
            "  record_id  VARCHAR(60)," +
            "  operation  VARCHAR(10)," +
            "  payload    CLOB," +
            "  synced     BOOLEAN DEFAULT FALSE" +
            ")"
        );

        s.execute(
            "CREATE TABLE IF NOT EXISTS formula_sheets (" +
            "  id          VARCHAR(36) DEFAULT RANDOM_UUID() PRIMARY KEY," +
            "  subject_id  VARCHAR(36)," +
            "  class_level VARCHAR(10)," +
            "  title       VARCHAR(150)," +
            "  content     TEXT," +
            "  is_active   BOOLEAN   DEFAULT TRUE" +
            ")"
        );

        s.execute(
            "CREATE TABLE IF NOT EXISTS result_appeals (" +
            "  id             VARCHAR(36) DEFAULT RANDOM_UUID() PRIMARY KEY," +
            "  result_id      VARCHAR(36)," +
            "  student_id     VARCHAR(36)," +
            "  subject_code   VARCHAR(30)," +
            "  reason         TEXT NOT NULL," +
            "  status         VARCHAR(20) DEFAULT 'OPEN'," +
            "  admin_response TEXT," +
            "  resolved_by    VARCHAR(36)," +
            "  created_at     TIMESTAMP   DEFAULT CURRENT_TIMESTAMP," +
            "  resolved_at    TIMESTAMP" +
            ")"
        );

        s.execute(
            "CREATE TABLE IF NOT EXISTS notification_queue (" +
            "  id              VARCHAR(36)  DEFAULT RANDOM_UUID() PRIMARY KEY," +
            "  recipient_email VARCHAR(150)," +
            "  recipient_phone VARCHAR(30)," +
            "  subject         VARCHAR(200)," +
            "  body            TEXT," +
            "  channel         VARCHAR(10)  DEFAULT 'EMAIL'," +
            "  status          VARCHAR(20)  DEFAULT 'PENDING'," +
            "  sent_at         TIMESTAMP," +
            "  created_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP" +
            ")"
        );

        // KLC v1.0: social module tables (Profile / Friends / Messages).
        // These mirror the Supabase migration in
        // supabase/klc_supabase_schema_v1_0_social.sql so that offline
        // (H2) and cloud (PostgreSQL) stay schema-compatible.
        s.execute(
            "CREATE TABLE IF NOT EXISTS user_profiles (" +
            "  id            VARCHAR(36) DEFAULT RANDOM_UUID() PRIMARY KEY," +
            "  user_id       VARCHAR(36) UNIQUE," +
            "  photo_url     TEXT," +
            "  bio           TEXT," +
            "  date_of_birth DATE," +
            "  address       TEXT," +
            "  updated_at    TIMESTAMP   DEFAULT CURRENT_TIMESTAMP" +
            ")"
        );

        s.execute(
            "CREATE TABLE IF NOT EXISTS friendships (" +
            "  id           VARCHAR(36) DEFAULT RANDOM_UUID() PRIMARY KEY," +
            "  requester_id VARCHAR(36)," +
            "  receiver_id  VARCHAR(36)," +
            "  status       VARCHAR(20) DEFAULT 'PENDING'," +
            "  created_at   TIMESTAMP   DEFAULT CURRENT_TIMESTAMP" +
            ")"
        );

        s.execute(
            "CREATE TABLE IF NOT EXISTS messages (" +
            "  id          VARCHAR(36) DEFAULT RANDOM_UUID() PRIMARY KEY," +
            "  sender_id   VARCHAR(36)," +
            "  receiver_id VARCHAR(36)," +
            "  content     TEXT," +
            "  is_read     BOOLEAN     DEFAULT FALSE," +
            "  created_at  TIMESTAMP   DEFAULT CURRENT_TIMESTAMP" +
            ")"
        );

        // KLC v1.0: Parent Portal - links a parent account to a ward by
        // admission number (read-only access to the ward's results).
        s.execute(
            "CREATE TABLE IF NOT EXISTS parent_profiles (" +
            "  id                VARCHAR(36) DEFAULT RANDOM_UUID() PRIMARY KEY," +
            "  user_id           VARCHAR(36) UNIQUE," +
            "  ward_admission_no VARCHAR(40)," +
            "  relationship      VARCHAR(30) DEFAULT 'GUARDIAN'" +
            ")"
        );

        System.out.println("[DB] H2: all 30 tables created/verified");
    }

    /**
     * Upgrades an EXISTING H2 offline cache in place.
     * CREATE TABLE IF NOT EXISTS does nothing when the table already
     * exists, so databases created before v1.0 would be missing the
     * new columns. These ALTERs are idempotent and safe on every start.
     */
    private static void ensureH2Columns(Statement s) {
        String[] alters = {
            "ALTER TABLE announcements ADD COLUMN IF NOT EXISTS " +
                "status VARCHAR(20) DEFAULT 'APPROVED'",
            "ALTER TABLE users ADD COLUMN IF NOT EXISTS " +
                "phone_type VARCHAR(20)",
            "ALTER TABLE users ADD COLUMN IF NOT EXISTS " +
                "phone_contact VARCHAR(20)",
            "ALTER TABLE questions ADD COLUMN IF NOT EXISTS " +
                "topic VARCHAR(150)",
            "ALTER TABLE school_profile ADD COLUMN IF NOT EXISTS " +
                "campus_name VARCHAR(120)"
        };
        for (String sql : alters) {
            try { s.execute(sql); } catch (Exception ignored) {}
        }
        // KLC v1.0: parent portal table for offline caches created before
        // the social/parent release.
        try {
            s.execute(
                "CREATE TABLE IF NOT EXISTS parent_profiles (" +
                "  id                VARCHAR(36) DEFAULT RANDOM_UUID() PRIMARY KEY," +
                "  user_id           VARCHAR(36) UNIQUE," +
                "  ward_admission_no VARCHAR(40)," +
                "  relationship      VARCHAR(30) DEFAULT 'GUARDIAN'" +
                ")");
        } catch (Exception ignored) {}
        System.out.println("[DB] H2 columns verified (v1.0)");
    }

    private static void ensurePostgresColumns(Statement s) {
        String[] alters = {
            "ALTER TABLE users ADD COLUMN IF NOT EXISTS totp_enabled BOOLEAN DEFAULT FALSE",
            "ALTER TABLE users ADD COLUMN IF NOT EXISTS totp_secret VARCHAR(100)",
            "ALTER TABLE users ADD COLUMN IF NOT EXISTS password_changed_at TIMESTAMP DEFAULT now()",
            "ALTER TABLE users ADD COLUMN IF NOT EXISTS phone_type VARCHAR(20)",
            "ALTER TABLE users ADD COLUMN IF NOT EXISTS phone_contact VARCHAR(20)",
            "ALTER TABLE announcements ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'APPROVED'",
            "ALTER TABLE study_materials ADD COLUMN IF NOT EXISTS title VARCHAR(200)",
            "CREATE TABLE IF NOT EXISTS formula_sheets (" +
                "id UUID PRIMARY KEY DEFAULT uuid_generate_v4()," +
                "subject_id UUID REFERENCES subjects(id)," +
                "class_level VARCHAR(10)," +
                "title VARCHAR(150)," +
                "content TEXT," +
                "is_active BOOLEAN DEFAULT TRUE)",
            "CREATE TABLE IF NOT EXISTS notification_queue (" +
                "id UUID PRIMARY KEY DEFAULT uuid_generate_v4()," +
                "recipient_email VARCHAR(150)," +
                "recipient_phone VARCHAR(30)," +
                "subject VARCHAR(200)," +
                "body TEXT," +
                "channel VARCHAR(10) DEFAULT 'EMAIL'," +
                "status VARCHAR(20) DEFAULT 'PENDING'," +
                "sent_at TIMESTAMP," +
                "created_at TIMESTAMP DEFAULT now())",
            // KLC v1.0: social module tables - receiver_id matches the
            // live cloud schema (NOT addressee_id - see FriendsController).
            "CREATE TABLE IF NOT EXISTS user_profiles (" +
                "id UUID PRIMARY KEY DEFAULT uuid_generate_v4()," +
                "user_id UUID UNIQUE REFERENCES users(id)," +
                "photo_url TEXT," +
                "bio TEXT," +
                "date_of_birth DATE," +
                "address TEXT," +
                "updated_at TIMESTAMP DEFAULT now())",
            "CREATE TABLE IF NOT EXISTS friendships (" +
                "id UUID PRIMARY KEY DEFAULT uuid_generate_v4()," +
                "requester_id UUID REFERENCES users(id)," +
                "receiver_id UUID REFERENCES users(id)," +
                "status VARCHAR(20) DEFAULT 'PENDING'," +
                "created_at TIMESTAMP DEFAULT now())",
            "CREATE TABLE IF NOT EXISTS messages (" +
                "id UUID PRIMARY KEY DEFAULT uuid_generate_v4()," +
                "sender_id UUID REFERENCES users(id)," +
                "receiver_id UUID REFERENCES users(id)," +
                "content TEXT," +
                "is_read BOOLEAN DEFAULT FALSE," +
                "created_at TIMESTAMP DEFAULT now())",
            // KLC v1.0: topics power the topic-by-topic analytics breakdown
            "ALTER TABLE questions ADD COLUMN IF NOT EXISTS topic VARCHAR(150)",
            // KLC v1.0: multi-campus profile field
            "ALTER TABLE school_profile ADD COLUMN IF NOT EXISTS campus_name VARCHAR(120)",
            // KLC v1.0: parent portal
            "CREATE TABLE IF NOT EXISTS parent_profiles (" +
                "id UUID PRIMARY KEY DEFAULT uuid_generate_v4()," +
                "user_id UUID UNIQUE REFERENCES users(id)," +
                "ward_admission_no VARCHAR(40)," +
                "relationship VARCHAR(30) DEFAULT 'GUARDIAN')"
        };
        for (String sql : alters) {
            try { s.execute(sql); } catch (Exception ignored) {}
        }
        System.out.println("[DB] PostgreSQL columns verified");
    }

    private static void seedSuperAdmin(Connection conn) {
        try {
            try (PreparedStatement check = conn.prepareStatement(
                    "SELECT COUNT(*) FROM users WHERE role = 'SUPER_ADMIN'")) {
                ResultSet rs = check.executeQuery();
                if (rs.next() && rs.getInt(1) > 0) {
                    System.out.println("[DB] H2 SuperAdmin already exists");
                    return;
                }
            }

            String uid = UUID.randomUUID().toString();
            String plainPw = seedPassword();
            String hash = at.favre.lib.crypto.bcrypt.BCrypt.withDefaults()
                .hashToString(12, plainPw.toCharArray());

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users(" +
                    "  id, full_name, email, password_hash, role, " +
                    "  phone, is_active, totp_enabled, " +
                    "  password_changed_at, created_at) " +
                    "VALUES(?,?,?,?,'SUPER_ADMIN',?,TRUE,FALSE," +
                    "  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
                ps.setString(1, uid);
                ps.setString(2, SA_NAME);
                ps.setString(3, SA_EMAIL);
                ps.setString(4, hash);
                ps.setString(5, SA_PHONE);
                ps.executeUpdate();
            }

            System.out.println("[DB] H2 SuperAdmin seeded");
            System.out.println("[DB] Email:    " + SA_EMAIL);
            System.out.println("[DB] Password: " + plainPw
                + "   <- shown ONCE; change it after first login"
                + " (or set app.superadmin.password in config.properties)");

        } catch (Exception e) {
            System.err.println("[DB] SuperAdmin seed error: " + e.getMessage());
        }
    }

    private static void seedSubjects(Connection conn) {
        try {
            try (PreparedStatement check = conn.prepareStatement(
                    "SELECT COUNT(*) FROM subjects")) {
                ResultSet rs = check.executeQuery();
                if (rs.next() && rs.getInt(1) > 0) {
                    System.out.println("[DB] Subjects already seeded");
                    return;
                }
            }

            String saId = null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id FROM users WHERE role = 'SUPER_ADMIN' LIMIT 1")) {
                ResultSet rs = ps.executeQuery();
                if (rs.next()) saId = rs.getString(1);
            }

            String[][] subjects = {
                {"ACCOUNTING",                    "ACC-SS1",   "SS1"},
                {"ACCOUNTING",                    "ACC-SS2",   "SS2"},
                {"ACCOUNTING",                    "ACC-SS3",   "SS3"},
                {"AGRICULTURAL SCIENCE",          "AGR-JSS1",  "JSS1"},
                {"AGRICULTURAL SCIENCE",          "AGR-JSS2",  "JSS2"},
                {"AGRICULTURAL SCIENCE",          "AGR-JSS3",  "JSS3"},
                {"AGRICULTURAL SCIENCE",          "AGR-SS1",   "SS1"},
                {"AGRICULTURAL SCIENCE",          "AGR-SS2",   "SS2"},
                {"AGRICULTURAL SCIENCE",          "AGR-SS3",   "SS3"},
                {"BASIC SCIENCE",                 "BSC-JSS1",  "JSS1"},
                {"BASIC SCIENCE",                 "BSC-JSS2",  "JSS2"},
                {"BASIC SCIENCE",                 "BSC-JSS3",  "JSS3"},
                {"BASIC TECHNOLOGY",              "BTH-JSS1",  "JSS1"},
                {"BASIC TECHNOLOGY",              "BTH-JSS2",  "JSS2"},
                {"BASIC TECHNOLOGY",              "BTH-JSS3",  "JSS3"},
                {"BIOLOGY",                       "BIO-SS1",   "SS1"},
                {"BIOLOGY",                       "BIO-SS2",   "SS2"},
                {"BIOLOGY",                       "BIO-SS3",   "SS3"},
                {"BUSINESS STUDIES",              "BUS-JSS1",  "JSS1"},
                {"BUSINESS STUDIES",              "BUS-JSS2",  "JSS2"},
                {"BUSINESS STUDIES",              "BUS-JSS3",  "JSS3"},
                {"CHEMISTRY",                     "CHM-SS1",   "SS1"},
                {"CHEMISTRY",                     "CHM-SS2",   "SS2"},
                {"CHEMISTRY",                     "CHM-SS3",   "SS3"},
                {"CHRISTIAN RELIGIOUS STUDIES",   "CRS-JSS1",  "JSS1"},
                {"CHRISTIAN RELIGIOUS STUDIES",   "CRS-JSS2",  "JSS2"},
                {"CHRISTIAN RELIGIOUS STUDIES",   "CRS-JSS3",  "JSS3"},
                {"CHRISTIAN RELIGIOUS STUDIES",   "CRS-SS1",   "SS1"},
                {"CHRISTIAN RELIGIOUS STUDIES",   "CRS-SS2",   "SS2"},
                {"CHRISTIAN RELIGIOUS STUDIES",   "CRS-SS3",   "SS3"},
                {"CIVIC EDUCATION",               "CIV-JSS1",  "JSS1"},
                {"CIVIC EDUCATION",               "CIV-JSS2",  "JSS2"},
                {"CIVIC EDUCATION",               "CIV-JSS3",  "JSS3"},
                {"CIVIC EDUCATION",               "CIV-SS1",   "SS1"},
                {"CIVIC EDUCATION",               "CIV-SS2",   "SS2"},
                {"CIVIC EDUCATION",               "CIV-SS3",   "SS3"},
                {"COMMERCE",                      "COM-SS1",   "SS1"},
                {"COMMERCE",                      "COM-SS2",   "SS2"},
                {"COMMERCE",                      "COM-SS3",   "SS3"},
                {"CULTURAL AND CREATIVE ART",     "CCA-JSS1",  "JSS1"},
                {"CULTURAL AND CREATIVE ART",     "CCA-JSS2",  "JSS2"},
                {"CULTURAL AND CREATIVE ART",     "CCA-JSS3",  "JSS3"},
                {"DATA PROCESSING",               "DTP-SS1",   "SS1"},
                {"DATA PROCESSING",               "DTP-SS2",   "SS2"},
                {"DATA PROCESSING",               "DTP-SS3",   "SS3"},
                {"DIGITAL TECHNOLOGY",            "DGT-JSS1",  "JSS1"},
                {"DIGITAL TECHNOLOGY",            "DGT-JSS2",  "JSS2"},
                {"DIGITAL TECHNOLOGY",            "DGT-JSS3",  "JSS3"},
                {"ECONOMICS",                     "ECO-SS1",   "SS1"},
                {"ECONOMICS",                     "ECO-SS2",   "SS2"},
                {"ECONOMICS",                     "ECO-SS3",   "SS3"},
                {"ENGLISH LANGUAGE",              "ENG-JSS1",  "JSS1"},
                {"ENGLISH LANGUAGE",              "ENG-JSS2",  "JSS2"},
                {"ENGLISH LANGUAGE",              "ENG-JSS3",  "JSS3"},
                {"ENGLISH LANGUAGE",              "ENG-SS1",   "SS1"},
                {"ENGLISH LANGUAGE",              "ENG-SS2",   "SS2"},
                {"ENGLISH LANGUAGE",              "ENG-SS3",   "SS3"},
                {"FRENCH",                        "FRN-JSS1",  "JSS1"},
                {"FRENCH",                        "FRN-JSS2",  "JSS2"},
                {"FRENCH",                        "FRN-JSS3",  "JSS3"},
                {"FRENCH",                        "FRN-SS1",   "SS1"},
                {"FRENCH",                        "FRN-SS2",   "SS2"},
                {"FRENCH",                        "FRN-SS3",   "SS3"},
                {"FURTHER MATHEMATICS",           "FMT-SS1",   "SS1"},
                {"FURTHER MATHEMATICS",           "FMT-SS2",   "SS2"},
                {"FURTHER MATHEMATICS",           "FMT-SS3",   "SS3"},
                {"GEOGRAPHY",                     "GEO-SS1",   "SS1"},
                {"GEOGRAPHY",                     "GEO-SS2",   "SS2"},
                {"GEOGRAPHY",                     "GEO-SS3",   "SS3"},
                {"GOVERNMENT",                    "GOV-SS1",   "SS1"},
                {"GOVERNMENT",                    "GOV-SS2",   "SS2"},
                {"GOVERNMENT",                    "GOV-SS3",   "SS3"},
                {"HAUSA LANGUAGE",                "HAS-JSS1",  "JSS1"},
                {"HAUSA LANGUAGE",                "HAS-JSS2",  "JSS2"},
                {"HAUSA LANGUAGE",                "HAS-JSS3",  "JSS3"},
                {"HAUSA LANGUAGE",                "HAS-SS1",   "SS1"},
                {"HAUSA LANGUAGE",                "HAS-SS2",   "SS2"},
                {"HAUSA LANGUAGE",                "HAS-SS3",   "SS3"},
                {"HOME ECONOMICS",                "HEC-JSS1",  "JSS1"},
                {"HOME ECONOMICS",                "HEC-JSS2",  "JSS2"},
                {"HOME ECONOMICS",                "HEC-JSS3",  "JSS3"},
                {"IGBO LANGUAGE",                 "IGB-JSS1",  "JSS1"},
                {"IGBO LANGUAGE",                 "IGB-JSS2",  "JSS2"},
                {"IGBO LANGUAGE",                 "IGB-JSS3",  "JSS3"},
                {"IGBO LANGUAGE",                 "IGB-SS1",   "SS1"},
                {"IGBO LANGUAGE",                 "IGB-SS2",   "SS2"},
                {"IGBO LANGUAGE",                 "IGB-SS3",   "SS3"},
                {"ISLAMIC RELIGIOUS KNOWLEDGE",   "IRK-JSS1",  "JSS1"},
                {"ISLAMIC RELIGIOUS KNOWLEDGE",   "IRK-JSS2",  "JSS2"},
                {"ISLAMIC RELIGIOUS KNOWLEDGE",   "IRK-JSS3",  "JSS3"},
                {"ISLAMIC RELIGIOUS KNOWLEDGE",   "IRS-SS1",   "SS1"},
                {"ISLAMIC RELIGIOUS KNOWLEDGE",   "IRS-SS2",   "SS2"},
                {"ISLAMIC RELIGIOUS KNOWLEDGE",   "IRS-SS3",   "SS3"},
                {"LITERATURE IN ENGLISH",         "LIT-SS1",   "SS1"},
                {"LITERATURE IN ENGLISH",         "LIT-SS2",   "SS2"},
                {"LITERATURE IN ENGLISH",         "LIT-SS3",   "SS3"},
                {"MATHEMATICS",                   "MTH-JSS1",  "JSS1"},
                {"MATHEMATICS",                   "MTH-JSS2",  "JSS2"},
                {"MATHEMATICS",                   "MTH-JSS3",  "JSS3"},
                {"MATHEMATICS",                   "MTH-SS1",   "SS1"},
                {"MATHEMATICS",                   "MTH-SS2",   "SS2"},
                {"MATHEMATICS",                   "MTH-SS3",   "SS3"},
                {"OFFICE PRACTICE",               "OFP-SS1",   "SS1"},
                {"OFFICE PRACTICE",               "OFP-SS2",   "SS2"},
                {"OFFICE PRACTICE",               "OFP-SS3",   "SS3"},
                {"PHYSICAL HEALTH EDUCATION",     "PHE-JSS1",  "JSS1"},
                {"PHYSICAL HEALTH EDUCATION",     "PHE-JSS2",  "JSS2"},
                {"PHYSICAL HEALTH EDUCATION",     "PHE-JSS3",  "JSS3"},
                {"PHYSICAL HEALTH EDUCATION",     "PHE-SS1",   "SS1"},
                {"PHYSICAL HEALTH EDUCATION",     "PHE-SS2",   "SS2"},
                {"PHYSICAL HEALTH EDUCATION",     "PHE-SS3",   "SS3"},
                {"PHYSICS",                       "PHY-SS1",   "SS1"},
                {"PHYSICS",                       "PHY-SS2",   "SS2"},
                {"PHYSICS",                       "PHY-SS3",   "SS3"},
                {"SECURITY EDUCATION",            "SEC-JSS1",  "JSS1"},
                {"SECURITY EDUCATION",            "SEC-JSS2",  "JSS2"},
                {"SECURITY EDUCATION",            "SEC-JSS3",  "JSS3"},
                {"SOCIAL STUDIES",                "SST-JSS1",  "JSS1"},
                {"SOCIAL STUDIES",                "SST-JSS2",  "JSS2"},
                {"SOCIAL STUDIES",                "SST-JSS3",  "JSS3"},
                {"TECHNICAL DRAWING",             "TDW-SS1",   "SS1"},
                {"TECHNICAL DRAWING",             "TDW-SS2",   "SS2"},
                {"TECHNICAL DRAWING",             "TDW-SS3",   "SS3"},
                {"TRADE SUBJECT",                 "TRD-SS1",   "SS1"},
                {"TRADE SUBJECT",                 "TRD-SS2",   "SS2"},
                {"TRADE SUBJECT",                 "TRD-SS3",   "SS3"},
                {"VISUAL ARTS",                   "VAS-SS1",   "SS1"},
                {"VISUAL ARTS",                   "VAS-SS2",   "SS2"},
                {"VISUAL ARTS",                   "VAS-SS3",   "SS3"},
                {"YORUBA LANGUAGE",               "YRB-JSS1",  "JSS1"},
                {"YORUBA LANGUAGE",               "YRB-JSS2",  "JSS2"},
                {"YORUBA LANGUAGE",               "YRB-JSS3",  "JSS3"},
                {"YORUBA LANGUAGE",               "YRB-SS1",   "SS1"},
                {"YORUBA LANGUAGE",               "YRB-SS2",   "SS2"},
                {"YORUBA LANGUAGE",               "YRB-SS3",   "SS3"},
            };

            int inserted = 0;
            for (String[] sub : subjects) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO subjects(" +
                        "  id, subject_name, subject_code, class_level, " +
                        "  is_active, created_by) " +
                        "SELECT RANDOM_UUID(), ?, ?, ?, TRUE, ? " +
                        "WHERE NOT EXISTS (" +
                        "  SELECT 1 FROM subjects WHERE subject_code = ?)")) {
                    ps.setString(1, sub[0]);
                    ps.setString(2, sub[1]);
                    ps.setString(3, sub[2]);
                    ps.setString(4, saId);
                    ps.setString(5, sub[1]);
                    inserted += ps.executeUpdate();
                } catch (Exception ignored) {}
            }

            System.out.println("[DB] Subjects seeded: " + inserted);

        } catch (Exception e) {
            System.err.println("[DB] Subject seed error: " + e.getMessage());
        }
    }

    private static void seedSchoolProfile(Connection conn) {
        try {
            try (PreparedStatement check = conn.prepareStatement(
                    "SELECT COUNT(*) FROM school_profile")) {
                ResultSet rs = check.executeQuery();
                if (rs.next() && rs.getInt(1) > 0) return;
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO school_profile(" +
                    "  id, school_name, motto, principal_name, " +
                    "  email, phone, session_current, term_current) " +
                    "VALUES(RANDOM_UUID(), ?, ?, ?, ?, ?, '2024/2025', '1st')")) {
                ps.setString(1, "KNOWLEDGE LAND COLLEGE");
                ps.setString(2, "Knowledge is Power");
                ps.setString(3, "OLUFEMI BENUA KERIPE");
                ps.setString(4, "femzykenterprisesltd@gmail.com");
                ps.setString(5, "+2349049903679");
                ps.executeUpdate();
            }

            System.out.println("[DB] School profile seeded");

        } catch (Exception e) {
            System.err.println("[DB] School profile seed error: " + e.getMessage());
        }
    }
}

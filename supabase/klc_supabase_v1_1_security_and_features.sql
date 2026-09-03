-- ============================================================================
-- KLC CBT SUITE v1.1 - SECURITY & FEATURES MIGRATION
-- Run in the Supabase SQL editor AFTER klc_supabase_schema.sql (+ v6_2 /
-- v6_3_final if present). Idempotent: safe to run on every deployment.
--
-- Covers the four v1.0 promises that are enforced in the DATABASE:
--   1. WORM audit trail        (Enterprise tier claim - now real)
--   2. RLS baseline            (v6_3_final shipped with RLS disabled)
--   3. Chat attachment storage (Supabase Storage bucket)
--   4. Parent Portal + topics + multi-campus schema
--
-- Powered by FEMZYK | OLUFEMI BENUA KERIPE
-- ============================================================================

-- ── 0. Schema top-ups (harmless if the app already created these) ──────────
ALTER TABLE questions      ADD COLUMN IF NOT EXISTS topic VARCHAR(150);
ALTER TABLE school_profile ADD COLUMN IF NOT EXISTS campus_name VARCHAR(120);

-- v1.0 parent portal: the original schema's role CHECK constraint does NOT
-- include 'PARENT', so parent registration failed on cloud projects
-- (offline/H2 has no such constraint - which is why it worked locally).
-- Drop the old role check and re-add it with PARENT. Idempotent.
DO $$
DECLARE r RECORD;
BEGIN
  FOR r IN
    SELECT con.conname
    FROM pg_constraint con
    JOIN pg_class rel ON rel.oid = con.conrelid
    WHERE rel.relname = 'users'
      AND con.contype = 'c'
      AND con.consrc::text ILIKE '%role in (%)%'
      AND con.consrc::text NOT ILIKE '%parent%'
  LOOP
    EXECUTE format('ALTER TABLE users DROP CONSTRAINT %I', r.conname);
  END LOOP;
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint con
    JOIN pg_class rel ON rel.oid = con.conrelid
    WHERE rel.relname = 'users'
      AND con.contype = 'c'
      AND con.consrc::text ILIKE '%role in (%)%'
      AND con.consrc::text ILIKE '%parent%'
  ) THEN
    ALTER TABLE users ADD CONSTRAINT users_role_v2
      CHECK (role IN ('SUPER_ADMIN','PRINCIPAL_ADMIN','EXAM_OFFICER',
                      'TEACHER','STUDENT','PARENT'));
  END IF;
END $$;

CREATE TABLE IF NOT EXISTS parent_profiles (
  id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id           UUID UNIQUE REFERENCES users(id) ON DELETE CASCADE,
  ward_admission_no VARCHAR(40),
  relationship      VARCHAR(30) DEFAULT 'GUARDIAN'
);
CREATE INDEX IF NOT EXISTS idx_parent_profiles_ward
  ON parent_profiles(ward_admission_no);

-- Social module (matches the desktop app - receiver_id, NOT addressee_id)
CREATE TABLE IF NOT EXISTS user_profiles (
  id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id       UUID UNIQUE REFERENCES users(id) ON DELETE CASCADE,
  photo_url     TEXT,
  bio           TEXT,
  date_of_birth DATE,
  address       TEXT,
  updated_at    TIMESTAMP DEFAULT now()
);
CREATE TABLE IF NOT EXISTS friendships (
  id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  requester_id UUID REFERENCES users(id) ON DELETE CASCADE,
  receiver_id  UUID REFERENCES users(id) ON DELETE CASCADE,
  status       VARCHAR(20) DEFAULT 'PENDING'
               CHECK (status IN ('PENDING','ACCEPTED','DECLINED')),
  created_at   TIMESTAMP DEFAULT now()
);
CREATE TABLE IF NOT EXISTS messages (
  id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  sender_id   UUID REFERENCES users(id) ON DELETE CASCADE,
  receiver_id UUID REFERENCES users(id) ON DELETE CASCADE,
  content     TEXT,
  is_read     BOOLEAN DEFAULT FALSE,
  created_at  TIMESTAMP DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_friendships_requester ON friendships(requester_id);
CREATE INDEX IF NOT EXISTS idx_friendships_receiver  ON friendships(receiver_id);
CREATE INDEX IF NOT EXISTS idx_messages_sender       ON messages(sender_id);
CREATE INDEX IF NOT EXISTS idx_messages_receiver     ON messages(receiver_id);
CREATE INDEX IF NOT EXISTS idx_attempt_answers_q     ON attempt_answers(question_id);

-- Backup history (referenced by BackupService/BackupController - was
-- missing from every schema before v1.1; history failed silently).
CREATE TABLE IF NOT EXISTS backup_logs (
  id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  backup_type VARCHAR(30),
  file_path   TEXT,
  file_size   BIGINT,
  checksum    VARCHAR(80),
  created_by  UUID,
  created_at  TIMESTAMP DEFAULT now()
);

-- Legacy offline caches may have created friendships with addressee_id.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns
             WHERE table_name = 'friendships'
               AND column_name = 'addressee_id')
     AND NOT EXISTS (SELECT 1 FROM information_schema.columns
                     WHERE table_name = 'friendships'
                       AND column_name = 'receiver_id') THEN
    ALTER TABLE friendships RENAME COLUMN addressee_id TO receiver_id;
  END IF;
END $$;

-- ── 1. WORM AUDIT TRAIL (Enterprise claim - enforce append-only) ───────────
-- audit_logs becomes INSERT-only at the database level. UPDATE/DELETE raise
-- an exception for every role (only the table owner can drop the trigger).
CREATE OR REPLACE FUNCTION klc_audit_logs_worm()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'audit_logs is WORM (write-once): % blocked',
    TG_OP;
END $$;

DROP TRIGGER IF EXISTS audit_logs_worm ON audit_logs;
CREATE TRIGGER audit_logs_worm
  BEFORE UPDATE OR DELETE ON audit_logs
  FOR EACH ROW EXECUTE FUNCTION klc_audit_logs_worm();

-- ── 2. RLS BASELINE (v6_3_final disabled RLS - re-enable safely) ───────────
-- The JavaFX desktop app connects as postgres/service_role which BYPASSES
-- RLS, so day-to-day CBT operations are unaffected. RLS protects any
-- anon-authenticated web/REST access to school data.
DO $$
DECLARE t TEXT;
BEGIN
  FOREACH t IN ARRAY ARRAY[
    'users','student_profiles','results','exam_attempts',
    'attempt_answers','ca_scores','questions','question_options',
    'exams','exam_questions','audit_logs','friendships','messages',
    'user_profiles','parent_profiles','fees_ledger','notification_queue'
  ] LOOP
    IF EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_name = t) THEN
      EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
      EXECUTE format('DROP POLICY IF EXISTS %I ON %I',
                     'svc_full_access_' || t, t);
      EXECUTE format(
        'CREATE POLICY %I ON %I FOR ALL TO service_role
           USING (true) WITH CHECK (true)',
        'svc_full_access_' || t, t);
    END IF;
  END LOOP;
END $$;

-- ── 3. STORAGE BUCKET for chat attachments (files must transfer!) ──────────
INSERT INTO storage.buckets (id, name, public)
VALUES ('klc-attachments', 'klc-attachments', true)
ON CONFLICT (id) DO NOTHING;

-- Public READ (receivers open attachments via the public object URL);
-- writes require an authenticated key (the desktop app uses the service/
-- anon key with x-upsert - see StorageService).
DROP POLICY IF EXISTS "klc attachments public read" ON storage.objects;
CREATE POLICY "klc attachments public read" ON storage.objects
  FOR SELECT USING (bucket_id = 'klc-attachments');

DROP POLICY IF EXISTS "klc attachments auth write" ON storage.objects;
CREATE POLICY "klc attachments auth write" ON storage.objects
  FOR INSERT TO service_role WITH CHECK (bucket_id = 'klc-attachments');

DROP POLICY IF EXISTS "klc attachments auth update" ON storage.objects;
CREATE POLICY "klc attachments auth update" ON storage.objects
  FOR UPDATE TO service_role USING (bucket_id = 'klc-attachments');

-- ── 4. Exam-window helper index (chat lock while exams are running) ────────
CREATE INDEX IF NOT EXISTS idx_exams_window
  ON exams(is_active, start_at, end_at);

-- ============================================================================
-- ROTATION NOTE: the historical super-admin password + BCrypt hash and the
-- registration codes were PUBLISHED in this repository (README + older SQL
-- files). They are now removed from the repo. Rotate them:
--   1. In Supabase: UPDATE users SET password_hash = '<new bcrypt>'
--      WHERE id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
--   2. Locally: set code.super_admin / code.admin / code.student in
--      config.properties (overrides the compiled defaults) and redeploy.
--   3. Store the new values as GitHub Secrets - see SECURITY_CREDENTIALS.md.
-- ============================================================================

-- ── 5. Question metadata (spec 4.3): year, Bloom's taxonomy, audio ──────────
ALTER TABLE questions      ADD COLUMN IF NOT EXISTS exam_year INT;
ALTER TABLE questions      ADD COLUMN IF NOT EXISTS bloom VARCHAR(20);
ALTER TABLE questions      ADD COLUMN IF NOT EXISTS question_audio_url TEXT;

-- ============================================================================
-- 6. WEB ADMIN PORTAL + PARENT RESULT CHECKER (klc-web-admin/)
--    Server-side RPCs used by the static Netlify/GitHub-Pages portal.
--    SECURITY DEFINER: the anon key can ONLY reach data through these
--    functions - admission/PIN and staff credentials are verified inside.
--    Requires the pgcrypto extension for bcrypt verification (Supabase
--    enables it by default; otherwise: create extension if not exists pgcrypto;)
-- ============================================================================
create extension if not exists pgcrypto;

-- Public parent checker: Admission No + Result PIN -> published results
CREATE OR REPLACE FUNCTION parent_lookup_results(
  p_admission TEXT, p_pin TEXT)
RETURNS TABLE(subject_code VARCHAR, class_level VARCHAR, term VARCHAR,
              session VARCHAR, score NUMERIC, total_questions INT,
              percentage NUMERIC, result_date TIMESTAMP)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM student_profiles
    WHERE admission_no = p_admission
      AND result_pin   = upper(trim(p_pin))
  ) THEN
    RAISE EXCEPTION 'Invalid admission number or result PIN';
  END IF;
  RETURN QUERY
  SELECT s.subject_code, e.class_level, COALESCE(e.term,'-'),
         COALESCE(e.session,'-'), r.score, r.total_questions,
         r.percentage, r.created_at
  FROM results r
  JOIN exams e     ON e.id = r.exam_id
  JOIN subjects s  ON s.id = e.subject_id
  JOIN student_profiles sp ON sp.user_id = r.student_id
  WHERE sp.admission_no = p_admission
    AND sp.result_pin   = upper(trim(p_pin))
    AND COALESCE(r.published, TRUE);
END $$;
GRANT EXECUTE ON FUNCTION parent_lookup_results(TEXT, TEXT) TO anon;

-- Staff identity check reused by every staff RPC (bcrypt verify)
CREATE OR REPLACE FUNCTION staff_check(p_email TEXT, p_password TEXT)
RETURNS VARCHAR  -- role when valid
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE v_role VARCHAR; v_hash TEXT;
BEGIN
  SELECT role, password_hash INTO v_role, v_hash
  FROM users WHERE lower(email) = lower(trim(p_email)) AND is_active;
  IF v_hash IS NULL THEN
    RAISE EXCEPTION 'Invalid credentials';
  END IF;
  IF NOT (crypt(p_password, v_hash) = v_hash) THEN
    RAISE EXCEPTION 'Invalid credentials';
  END IF;
  IF v_role NOT IN ('SUPER_ADMIN','PRINCIPAL_ADMIN','EXAM_OFFICER','TEACHER') THEN
    RAISE EXCEPTION 'Staff accounts only';
  END IF;
  RETURN v_role;
END $$;

-- Recent published results (staff)
CREATE OR REPLACE FUNCTION staff_recent_results(
  p_email TEXT, p_password TEXT, p_limit INT DEFAULT 100)
RETURNS TABLE(admission_no VARCHAR, student VARCHAR, subject_code VARCHAR,
              class_level VARCHAR, term VARCHAR, session VARCHAR,
              score NUMERIC, percentage NUMERIC, result_date TIMESTAMP)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
  PERFORM staff_check(p_email, p_password);
  RETURN QUERY
  SELECT sp.admission_no,
         sp.surname || ' ' || COALESCE(sp.other_names,''),
         s.subject_code, e.class_level, COALESCE(e.term,'-'),
         COALESCE(e.session,'-'), r.score, r.percentage, r.created_at
  FROM results r
  JOIN exams e    ON e.id = r.exam_id
  JOIN subjects s ON s.id = e.subject_id
  JOIN student_profiles sp ON sp.user_id = r.student_id
  WHERE COALESCE(r.published, TRUE)
  ORDER BY r.created_at DESC
  LIMIT LEAST(COALESCE(p_limit,100), 500);
END $$;
GRANT EXECUTE ON FUNCTION staff_recent_results(TEXT, TEXT, INT) TO anon;

-- Broadsheet rows for one class+session+term (staff) - CA + exam merged
CREATE OR REPLACE FUNCTION staff_broadsheet(
  p_email TEXT, p_password TEXT, p_class TEXT,
  p_session TEXT, p_term TEXT)
RETURNS TABLE(admission_no VARCHAR, student VARCHAR, subject_code VARCHAR,
              ca_total NUMERIC, exam_score NUMERIC, grand_total NUMERIC)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
  PERFORM staff_check(p_email, p_password);
  RETURN QUERY
  SELECT sp.admission_no,
         sp.surname || ' ' || COALESCE(sp.other_names,''),
         s.subject_code,
         COALESCE((SELECT SUM(cs.ca1_score + cs.ca2_score)
                   FROM ca_scores cs
                   WHERE cs.student_id = sp.user_id
                     AND cs.subject_id = s.id
                     AND cs.term = p_term), 0),
         r.score,
         COALESCE((SELECT SUM(cs.ca1_score + cs.ca2_score)
                   FROM ca_scores cs
                   WHERE cs.student_id = sp.user_id
                     AND cs.subject_id = s.id
                     AND cs.term = p_term), 0) + COALESCE(r.score, 0)
  FROM student_profiles sp
  JOIN exams e    ON e.class_level = sp.class_level
  JOIN subjects s ON s.id = e.subject_id
  LEFT JOIN results r ON r.exam_id = e.id AND r.student_id = sp.user_id
  WHERE sp.class_level = p_class
    AND COALESCE(e.session,'-') = p_session
    AND COALESCE(e.term,'-')    = p_term
    AND COALESCE(e.is_practice, FALSE) = FALSE
  ORDER BY sp.surname, s.subject_code;
END $$;
GRANT EXECUTE ON FUNCTION staff_broadsheet(TEXT, TEXT, TEXT, TEXT, TEXT) TO anon;

-- Subject directory (staff)
CREATE OR REPLACE FUNCTION staff_subjects(
  p_email TEXT, p_password TEXT)
RETURNS TABLE(subject_code VARCHAR, subject_name VARCHAR,
              class_level VARCHAR, is_active BOOLEAN)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
  PERFORM staff_check(p_email, p_password);
  RETURN QUERY SELECT s.subject_code, s.subject_name,
                      s.class_level, s.is_active
               FROM subjects s ORDER BY s.subject_code;
END $$;
GRANT EXECUTE ON FUNCTION staff_subjects(TEXT, TEXT) TO anon;

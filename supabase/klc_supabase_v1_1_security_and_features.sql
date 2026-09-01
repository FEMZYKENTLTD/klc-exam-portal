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

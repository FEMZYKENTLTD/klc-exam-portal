-- KNOWLEDGE LAND COLLEGE CBT SUITE v6.3 FINAL
-- Phase 2.3 – Completion Pack
-- Run AFTER klc_supabase_schema.sql + klc_supabase_schema_v6_2.sql
-- Supabase Project: <YOUR_PROJECT_REF>
-- Powered by FEMZYK | OLUFEMI BENUA KERIPE

-- 1. Super Admin password was rotated - hash no longer stored here.
UPDATE users SET password_hash = '<ROTATED_BCRYPT_HASH - set via Supabase SQL editor>',
  email = 'superadmin@knowledgeland.edu.ng'
WHERE id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';

-- 2. 2FA TOTP for Admins
ALTER TABLE users ADD COLUMN IF NOT EXISTS totp_secret VARCHAR(100);
ALTER TABLE users ADD COLUMN IF NOT EXISTS totp_enabled BOOLEAN DEFAULT false;
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_changed_at TIMESTAMP DEFAULT now();
ALTER TABLE users ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN DEFAULT false;

-- Password expiry: 90 days for staff
UPDATE users SET password_changed_at = now() WHERE password_changed_at IS NULL;

-- 3. Exam IP Whitelisting
ALTER TABLE exams ADD COLUMN IF NOT EXISTS allowed_ips TEXT; -- comma-separated, e.g. "192.168.0.0/24,10.0.0.5"
ALTER TABLE exams ADD COLUMN IF NOT EXISTS webcam_required BOOLEAN DEFAULT false;

-- 4. Question Version History
CREATE TABLE IF NOT EXISTS question_versions (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  question_id UUID REFERENCES questions(id) ON DELETE CASCADE,
  version INT NOT NULL,
  question_text TEXT,
  explanation TEXT,
  edited_by UUID REFERENCES users(id),
  edited_at TIMESTAMP DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_qv_question ON question_versions(question_id);

-- 5. Result QR verification code (stored)
ALTER TABLE results ADD COLUMN IF NOT EXISTS qr_verify_code VARCHAR(120);

-- 6. Formula sheets – ensure table exists
CREATE TABLE IF NOT EXISTS formula_sheets (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    subject_id UUID REFERENCES subjects(id),
    class_level VARCHAR(10),
    title VARCHAR(150),
    content TEXT,
    is_active BOOLEAN DEFAULT true
);

-- Seed formula sheets
INSERT INTO formula_sheets (subject_id, class_level, title, content)
SELECT s.id, s.class_level, 
  CASE WHEN s.subject_code LIKE 'MTH-%' THEN 'Mathematics Formula Sheet'
       WHEN s.subject_code LIKE 'PHY-%' THEN 'Physics Formula Sheet'
       WHEN s.subject_code LIKE 'CHM-%' THEN 'Chemistry Formula Sheet'
       ELSE 'General Formula Sheet' END,
  CASE WHEN s.subject_code LIKE 'MTH-%' THEN 
'Area = L × W
Volume = L × W × H
a² + b² = c²
Quadratic: x = (-b ± √(b²-4ac))/2a
Sin²θ + Cos²θ = 1'
       WHEN s.subject_code LIKE 'PHY-%' THEN
'v = u + at
s = ut + ½at²
F = ma
V = IR
P = IV'
       ELSE 'Refer to your textbook – No prohibited materials allowed'
  END
FROM subjects s
WHERE NOT EXISTS (SELECT 1 FROM formula_sheets fs WHERE fs.subject_id = s.id)
ON CONFLICT DO NOTHING;

-- 7. School branding – ensure logo / signature columns exist
ALTER TABLE school_profile ADD COLUMN IF NOT EXISTS logo_url TEXT;
ALTER TABLE school_profile ADD COLUMN IF NOT EXISTS principal_signature_url TEXT;

-- Update school profile – Principal: OLUFEMI BENUA KERIPE
UPDATE school_profile SET 
  school_name = 'KNOWLEDGE LAND COLLEGE',
  motto = 'Knowledge is Power',
  principal_name = 'OLUFEMI BENUA KERIPE',
  session_current = '2024/2025',
  term_current = '1st'
WHERE true;

-- 8. Announcements read receipts – already in v6_2

-- 9. Notification queue – email + SMS – already in v6_2

-- 10. Disable RLS for KLC v6.3 launch – Web Admin uses anon key
ALTER TABLE users DISABLE ROW LEVEL SECURITY;
ALTER TABLE student_profiles DISABLE ROW LEVEL SECURITY;
ALTER TABLE results DISABLE ROW LEVEL SECURITY;
ALTER TABLE questions DISABLE ROW LEVEL SECURITY;
ALTER TABLE exams DISABLE ROW LEVEL SECURITY;

-- Done – KLC CBT v6.3 FINAL
-- Super Admin: ROTATED - see SECURITY_CREDENTIALS.md
-- Teacher Code: FEMZYK
-- Super Admin Code: FEMZYK ENTERPRISES LTD
-- Result PIN: SURNAME+CLASS e.g. KERIPESS2
-- © 2025 KNOWLEDGE LAND COLLEGE – Powered by FEMZYK

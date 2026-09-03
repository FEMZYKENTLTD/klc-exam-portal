-- KNOWLEDGE LAND COLLEGE CBT SUITE v1.0 (schema history: originally shipped as v6.x)
-- Supabase PostgreSQL 15 Schema
-- Powered by FEMZYK | Lead: OLUFEMI BENUA KERIPE
-- $0 /mo Free Tier Ready

-- Enable extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ======================================================
-- 1. USERS / AUTH
-- ======================================================
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    full_name VARCHAR(150) NOT NULL,
    email VARCHAR(150) UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(30) NOT NULL CHECK (role IN ('SUPER_ADMIN','PRINCIPAL_ADMIN','EXAM_OFFICER','TEACHER','STUDENT','PARENT')),
    phone VARCHAR(30),
    is_active BOOLEAN DEFAULT true,
    security_question VARCHAR(255),
    security_answer_hash VARCHAR(255),
    failed_login_attempts INT DEFAULT 0,
    locked_until TIMESTAMP,
    created_at TIMESTAMP DEFAULT now(),
    updated_at TIMESTAMP DEFAULT now()
);

-- Student profiles
CREATE TABLE student_profiles (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    admission_no VARCHAR(40) UNIQUE NOT NULL,
    surname VARCHAR(80) NOT NULL,
    other_names VARCHAR(120),
    class_level VARCHAR(10) NOT NULL CHECK (class_level IN ('JSS1','JSS2','JSS3','SS1','SS2','SS3')),
    arm VARCHAR(20) CHECK (arm IN ('A','B','C','Science','Art','Commercial')),
    session VARCHAR(15) NOT NULL, -- e.g. 2024/2025
    gender VARCHAR(10),
    date_of_birth DATE,
    parent_phone VARCHAR(30),
    parent_email VARCHAR(150),
    address TEXT,
    passport_url TEXT,
    result_pin VARCHAR(40) UNIQUE NOT NULL, -- SURNAME+CLASS e.g. KERIPESS2
    fee_status VARCHAR(20) DEFAULT 'PAID' CHECK (fee_status IN ('PAID','PART','UNPAID')),
    status VARCHAR(20) DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','GRADUATED','WITHDRAWN','REPEATING')),
    created_at TIMESTAMP DEFAULT now()
);

-- ======================================================
-- 2. ACADEMIC
-- ======================================================
CREATE TABLE school_classes (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    class_level VARCHAR(10) NOT NULL,
    arm VARCHAR(20),
    class_teacher_id UUID REFERENCES users(id),
    session VARCHAR(15),
    is_active BOOLEAN DEFAULT true,
    UNIQUE(class_level, arm, session)
);

CREATE TABLE subjects (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    subject_name VARCHAR(120) NOT NULL,
    subject_code VARCHAR(30) UNIQUE NOT NULL, -- e.g. MTH-SS2, DTP-JSS1
    class_level VARCHAR(10),
    pass_mark INT DEFAULT 40,
    is_active BOOLEAN DEFAULT true,
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMP DEFAULT now()
);

CREATE TABLE teacher_subjects (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    teacher_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    subject_id UUID NOT NULL REFERENCES subjects(id) ON DELETE CASCADE,
    class_level VARCHAR(10),
    assigned_by UUID REFERENCES users(id), -- Super Admin override
    assigned_at TIMESTAMP DEFAULT now(),
    UNIQUE(teacher_id, subject_id, class_level)
);

-- ======================================================
-- 3. QUESTION BANK
-- ======================================================
CREATE TABLE questions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    subject_id UUID NOT NULL REFERENCES subjects(id),
    class_level VARCHAR(10),
    term VARCHAR(10) CHECK (term IN ('1st','2nd','3rd')),
    topic VARCHAR(150),
    difficulty VARCHAR(10) CHECK (difficulty IN ('Easy','Medium','Hard')),
    question_text TEXT NOT NULL,
    question_image_url TEXT,
    question_type VARCHAR(20) DEFAULT 'MCQ' CHECK (question_type IN ('MCQ','TRUE_FALSE','IMAGE')),
    explanation TEXT,
    source VARCHAR(80), -- e.g. WAEC 2023
    marks INT DEFAULT 1,
    is_approved BOOLEAN DEFAULT false,
    created_by UUID REFERENCES users(id),
    on_behalf_of UUID REFERENCES users(id), -- Super Admin uploading for teacher
    created_at TIMESTAMP DEFAULT now(),
    version INT DEFAULT 1
);

CREATE TABLE question_options (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    question_id UUID NOT NULL REFERENCES questions(id) ON DELETE CASCADE,
    option_label CHAR(1) NOT NULL CHECK (option_label IN ('A','B','C','D','E')),
    option_text TEXT NOT NULL,
    is_correct BOOLEAN DEFAULT false,
    UNIQUE(question_id, option_label)
);

CREATE TABLE question_attachments (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    question_id UUID REFERENCES questions(id) ON DELETE CASCADE,
    file_url TEXT,
    file_type VARCHAR(30)
);

-- ======================================================
-- 4. EXAMS
-- ======================================================
CREATE TABLE exams (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    subject_id UUID NOT NULL REFERENCES subjects(id),
    class_level VARCHAR(10) NOT NULL,
    arm VARCHAR(20),
    term VARCHAR(10),
    session VARCHAR(15),
    title VARCHAR(200),
    instructions TEXT,
    duration_minutes INT NOT NULL,
    total_marks INT,
    pass_mark INT,
    start_at TIMESTAMP,
    end_at TIMESTAMP,
    attempt_limit INT DEFAULT 1,
    is_practice BOOLEAN DEFAULT false,
    fee_gate BOOLEAN DEFAULT false,
    negative_marking NUMERIC(3,2) DEFAULT 0,
    is_active BOOLEAN DEFAULT true,
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMP DEFAULT now()
);

CREATE TABLE exam_questions (
    exam_id UUID REFERENCES exams(id) ON DELETE CASCADE,
    question_id UUID REFERENCES questions(id),
    question_order INT,
    PRIMARY KEY (exam_id, question_id)
);

-- ======================================================
-- 5. ATTEMPTS / RESULTS
-- ======================================================
CREATE TABLE exam_attempts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    exam_id UUID NOT NULL REFERENCES exams(id),
    student_id UUID NOT NULL REFERENCES users(id),
    admission_no VARCHAR(40),
    variant CHAR(1) DEFAULT 'A',
    started_at TIMESTAMP DEFAULT now(),
    submitted_at TIMESTAMP,
    time_remaining INT,
    strike_count INT DEFAULT 0,
    malpractice_log TEXT,
    ip_address VARCHAR(45),
    pc_name VARCHAR(100),
    status VARCHAR(20) DEFAULT 'IN_PROGRESS' CHECK (status IN ('IN_PROGRESS','SUBMITTED','MALPRACTICE','TIMED_OUT')),
    synced BOOLEAN DEFAULT true,
    UNIQUE(exam_id, student_id)
);

CREATE TABLE attempt_answers (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    attempt_id UUID NOT NULL REFERENCES exam_attempts(id) ON DELETE CASCADE,
    question_id UUID NOT NULL REFERENCES questions(id),
    selected_option CHAR(1),
    is_flagged BOOLEAN DEFAULT false,
    time_spent INT DEFAULT 0,
    answered_at TIMESTAMP DEFAULT now(),
    UNIQUE(attempt_id, question_id)
);

CREATE TABLE ca_scores (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    student_id UUID REFERENCES users(id),
    subject_id UUID REFERENCES subjects(id),
    class_level VARCHAR(10),
    term VARCHAR(10),
    session VARCHAR(15),
    ca1_score NUMERIC(5,2) DEFAULT 0,
    ca2_score NUMERIC(5,2) DEFAULT 0,
    exam_score NUMERIC(5,2),
    total_score NUMERIC(5,2),
    grade VARCHAR(5),
    remark VARCHAR(50),
    position INT,
    UNIQUE(student_id, subject_id, term, session)
);

CREATE TABLE results (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    attempt_id UUID UNIQUE REFERENCES exam_attempts(id),
    student_id UUID REFERENCES users(id),
    exam_id UUID REFERENCES exams(id),
    score NUMERIC(5,2),
    total_questions INT,
    correct_answers INT,
    percentage NUMERIC(5,2),
    grade VARCHAR(5),
    position INT,
    published BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT now()
);

CREATE TABLE result_pins (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    student_id UUID UNIQUE REFERENCES users(id),
    pin_code VARCHAR(40) UNIQUE NOT NULL,
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT now()
);

-- ======================================================
-- 6. OPERATIONS
-- ======================================================
CREATE TABLE school_profile (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    school_name VARCHAR(200) DEFAULT 'KNOWLEDGE LAND COLLEGE',
    address TEXT,
    motto VARCHAR(200),
    logo_url TEXT,
    principal_name VARCHAR(150),
    principal_signature_url TEXT,
    phone VARCHAR(50),
    email VARCHAR(150),
    grading_scale JSONB,
    session_current VARCHAR(15) DEFAULT '2024/2025',
    term_current VARCHAR(10) DEFAULT '1st',
    updated_at TIMESTAMP DEFAULT now()
);

CREATE TABLE announcements (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    title VARCHAR(200) NOT NULL,
    body TEXT NOT NULL,
    target_role VARCHAR(30) DEFAULT 'ALL',
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMP DEFAULT now(),
    expires_at TIMESTAMP
);

CREATE TABLE fees_ledger (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    student_id UUID REFERENCES users(id),
    term VARCHAR(10),
    session VARCHAR(15),
    amount_due NUMERIC(10,2),
    amount_paid NUMERIC(10,2),
    status VARCHAR(20) CHECK (status IN ('PAID','PART','UNPAID')),
    updated_at TIMESTAMP DEFAULT now()
);

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID REFERENCES users(id),
    action VARCHAR(120) NOT NULL,
    entity_type VARCHAR(60),
    entity_id UUID,
    details TEXT,
    ip_address VARCHAR(45),
    created_at TIMESTAMP DEFAULT now()
);

CREATE TABLE sync_queue (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    table_name VARCHAR(60) NOT NULL,
    record_id UUID NOT NULL,
    operation VARCHAR(10) CHECK (operation IN ('INSERT','UPDATE','DELETE')),
    payload JSONB,
    synced BOOLEAN DEFAULT false,
    attempts INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT now(),
    synced_at TIMESTAMP
);

-- ======================================================
-- INDEXES
-- ======================================================
CREATE INDEX idx_student_admission ON student_profiles(admission_no);
CREATE INDEX idx_results_student ON results(student_id);
CREATE INDEX idx_questions_subject ON questions(subject_id);
CREATE INDEX idx_attempts_student ON exam_attempts(student_id);
CREATE INDEX idx_audit_user ON audit_logs(user_id);

-- ======================================================
-- SEED DATA
-- ======================================================
-- School Profile
INSERT INTO school_profile (school_name, motto, principal_name) 
VALUES ('KNOWLEDGE LAND COLLEGE', 'Knowledge is Power', 'OLUFEMI BENUA KERIPE');

-- Super Admin (ROTATED - see SECURITY_CREDENTIALS.md; password no
-- longer stored in this repo. Set password_hash manually in Supabase.)
INSERT INTO users (id, full_name, email, password_hash, role) VALUES
('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'OLUFEMI BENUA KERIPE', 'superadmin@knowledgeland.edu.ng', '<ROTATED_BCRYPT_HASH - set via Supabase SQL editor>', 'SUPER_ADMIN');

-- Subjects – Pre-loaded
INSERT INTO subjects (subject_name, subject_code, class_level, created_by) VALUES
-- Digital Technology JSS
('DIGITAL TECHNOLOGY','DGT-JSS1','JSS1','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'),
('DIGITAL TECHNOLOGY','DGT-JSS2','JSS2','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'),
('DIGITAL TECHNOLOGY','DGT-JSS3','JSS3','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'),
-- Data Processing SS
('DATA PROCESSING','DTP-SS1','SS1','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'),
('DATA PROCESSING','DTP-SS2','SS2','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'),
('DATA PROCESSING','DTP-SS3','SS3','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'),
-- Core subjects
('MATHEMATICS','MTH-JSS1','JSS1','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'),
('MATHEMATICS','MTH-JSS2','JSS2','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'),
('MATHEMATICS','MTH-JSS3','JSS3','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'),
('MATHEMATICS','MTH-SS1','SS1','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'),
('MATHEMATICS','MTH-SS2','SS2','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'),
('MATHEMATICS','MTH-SS3','SS3','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'),
('ENGLISH LANGUAGE','ENG-JSS1','JSS1','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'),
('ENGLISH LANGUAGE','ENG-SS1','SS1','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'),
('ENGLISH LANGUAGE','ENG-SS2','SS2','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'),
('ENGLISH LANGUAGE','ENG-SS3','SS3','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'),
('PHYSICS','PHY-SS1','SS1','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'),
('PHYSICS','PHY-SS2','SS2','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'),
('PHYSICS','PHY-SS3','SS3','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'),
('CHEMISTRY','CHM-SS1','SS1','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'),
('BIOLOGY','BIO-SS1','SS1','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'),
('ECONOMICS','ECO-SS1','SS1','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'),
('GOVERNMENT','GOV-SS1','SS1','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'),
('LITERATURE IN ENGLISH','LIT-SS1','SS1','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'),
('AGRICULTURAL SCIENCE','AGR-JSS1','JSS1','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'),
('COMMERCE','COM-SS1','SS1','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'),
('ACCOUNTING','ACC-SS1','SS1','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa');

-- Super Admin login: ROTATED - see SECURITY_CREDENTIALS.md
-- Registration codes: SUPER_ADMIN = FEMZYK ENTERPRISES LTD, TEACHER/ADMIN = FEMZYK

-- Row Level Security - DISABLED at v6.x launch (legacy note).
-- Re-enabled with service_role policies by
-- klc_supabase_v1_1_security_and_features.sql (run it after this file).
-- Clients: JavaFX JDBC (postgres user – bypasses RLS), Web Admin (supabase-js anon key)
-- Enable RLS + policies in Phase 3 security hardening
ALTER TABLE users DISABLE ROW LEVEL SECURITY;
ALTER TABLE student_profiles DISABLE ROW LEVEL SECURITY;
ALTER TABLE results DISABLE ROW LEVEL SECURITY;

-- Done. KNOWLEDGE LAND COLLEGE CBT v6.3
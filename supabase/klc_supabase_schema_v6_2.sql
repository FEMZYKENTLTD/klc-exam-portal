-- KNOWLEDGE LAND COLLEGE CBT SUITE v6.2
-- Phase 2.2 – Full Enterprise Supplement
-- Run this AFTER klc_supabase_schema.sql
-- Powered by FEMZYK | OLUFEMI BENUA KERIPE

-- Result Appeals
CREATE TABLE IF NOT EXISTS result_appeals (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    result_id UUID REFERENCES results(id),
    student_id UUID REFERENCES users(id),
    subject_code VARCHAR(30),
    reason TEXT NOT NULL,
    status VARCHAR(20) DEFAULT 'OPEN' CHECK (status IN ('OPEN','IN_REVIEW','RESOLVED','REJECTED')),
    admin_response TEXT,
    resolved_by UUID REFERENCES users(id),
    created_at TIMESTAMP DEFAULT now(),
    resolved_at TIMESTAMP
);

-- ID Cards issued log
CREATE TABLE IF NOT EXISTS id_cards (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    student_id UUID REFERENCES users(id),
    admission_no VARCHAR(40),
    qr_data TEXT,
    issued_at TIMESTAMP DEFAULT now(),
    issued_by UUID REFERENCES users(id)
);

-- Email / SMS notification queue
CREATE TABLE IF NOT EXISTS notification_queue (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    recipient_email VARCHAR(150),
    recipient_phone VARCHAR(30),
    subject VARCHAR(200),
    body TEXT,
    channel VARCHAR(10) CHECK (channel IN ('EMAIL','SMS','BOTH')),
    status VARCHAR(20) DEFAULT 'PENDING',
    sent_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT now()
);

-- Study materials / E-Library
CREATE TABLE IF NOT EXISTS study_materials (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    subject_id UUID REFERENCES subjects(id),
    class_level VARCHAR(10),
    title VARCHAR(200),
    description TEXT,
    file_url TEXT,
    uploaded_by UUID REFERENCES users(id),
    created_at TIMESTAMP DEFAULT now()
);

-- Teacher bulk import staging
CREATE TABLE IF NOT EXISTS teacher_imports (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    full_name VARCHAR(150),
    email VARCHAR(150),
    staff_id VARCHAR(40),
    subjects_text TEXT,
    imported BOOLEAN DEFAULT false,
    created_at TIMESTAMP DEFAULT now()
);

-- System health / backup log
CREATE TABLE IF NOT EXISTS backup_logs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    backup_type VARCHAR(30),
    file_path TEXT,
    file_size BIGINT,
    checksum VARCHAR(128),
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMP DEFAULT now()
);

-- Make question_image_url support local + cloud
-- question_attachments table already exists

-- Announcements read receipts
CREATE TABLE IF NOT EXISTS announcement_reads (
    announcement_id UUID REFERENCES announcements(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    read_at TIMESTAMP DEFAULT now(),
    PRIMARY KEY (announcement_id, user_id)
);

-- Exam formula sheets
CREATE TABLE IF NOT EXISTS formula_sheets (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    subject_id UUID REFERENCES subjects(id),
    class_level VARCHAR(10),
    title VARCHAR(150),
    content TEXT,
    is_active BOOLEAN DEFAULT true
);

INSERT INTO formula_sheets (subject_id, class_level, title, content)
SELECT id, class_level, 'General Formula Sheet',
'Area = L × W\nVolume = L × W × H\na² + b² = c²\nQuadratic: x = (-b ± √(b²-4ac))/2a'
FROM subjects WHERE subject_code LIKE 'MTH-%' ON CONFLICT DO NOTHING;

-- Seed a sample appeal
-- result_appeals, notification_queue, study_materials ready

-- Indexes
CREATE INDEX IF NOT EXISTS idx_appeals_student ON result_appeals(student_id);
CREATE INDEX IF NOT EXISTS idx_notifications_status ON notification_queue(status);
CREATE INDEX IF NOT EXISTS idx_materials_subject ON study_materials(subject_id);

-- Done v6.2
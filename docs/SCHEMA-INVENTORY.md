# KNOWLEDGE LAND CBT SUITE — AUTHORITATIVE DATABASE INVENTORY

**Schema source of truth:** the H2 offline DDL in
`src/main/java/com/femzyk/klc/db/DatabaseInitializer.java` (single source the
desktop app creates/verifies on every startup) plus the cloud chain
`supabase/klc_supabase_schema.sql` → `v6_2` → `v6_3_final` →
`v1_0_social` → `v1_1_security_and_features.sql` (idempotent, applied on every
push to `main` and on every desktop startup against the cloud).

**Count:** **33 application tables** — 28 created by the desktop (H2/PostgreSQL
compatible DDL) and 5 cloud/social tables (`announcement_reads`, `id_cards`,
`question_attachments`, `question_versions`, `teacher_imports`) created by the
Supabase migration chain. The legacy “18 tables” figure in older
documentation is stale and is NOT used as the target: the real schema is the
authority. Identifiers are VARCHAR(36)/UUID interchangeably (desktop uses a
cross-DB `setUuid()` binder; cloud columns are `uuid`).

Legend: ✅ both engines · ☁️ cloud (Supabase) only.

| # | Table | Where | Purpose | PK | Key FKs / relationships | Important constraints & notes |
|---|-------|-------|---------|----|------------------------|-------------------------------|
| 1 | `users` | ✅ | One row per person: SUPER_ADMIN, PRINCIPAL_ADMIN, EXAM_OFFICER, TEACHER, STUDENT, PARENT. Auth: BCrypt hash, lockout, 2FA, password expiry | `id` | parent of `student_profiles`/`parent_profiles`/`teacher_subjects`; `created_by` self-ref | `email` UNIQUE; `role` CHECK (DB-enforced on cloud, app+DB on H2); `is_active`, `failed_login_attempts`, `locked_until`, `password_changed_at`, `totp_secret/verified` |
| 2 | `student_profiles` | ✅ | Academic identity of each student (admission, surname/other names, class, arm, session, DOB, gender, parent contacts, result PIN) | `id` | `user_id` → `users.id` UNIQUE | `admission_no` UNIQUE; `result_pin` UNIQUE; lifetime history via `class_level`+`session`+`status` (ACTIVE/REPEATING/GRADUATED/WITHDRAWN) |
| 3 | `parent_profiles` | ✅ | Links a PARENT account to a ward’s admission number (result checker) | `id` | `user_id` → `users.id`; ward by `ward_admission_no` (soft link to `student_profiles.admission_no`) | Parent portal is read-only |
| 4 | `school_profile` | ✅ | Single-row school identity (name, motto, principal, contact, current session/term, campus) | `id` | — | Singleton used by all reports & academic calendar |
| 5 | `school_classes` | ✅ | Class/arm/session registrations incl. class teacher | `id` | `class_teacher_id` → `users.id` | UNIQUE (class_level, arm, session); feeds arm balancing |
| 6 | `subjects` | ✅ | Subject catalogue: 135 rows seeded (19×JSS1-3 + 26×SS1-3 codes) | `id` | `created_by` → `users.id` | `subject_code` UNIQUE; `class_level`; `is_active`; seed idempotent (`WHERE NOT EXISTS`) |
| 7 | `teacher_subjects` | ✅ | Subjects a teacher may own (RBAC for question screens/uploads) | `id` | `teacher_id` → `users.id`; `subject_id` → `subjects.id` | Optional `class_level` scope |
| 8 | `questions` | ✅ | Question bank: MCQ/TRUE_FALSE/IMAGE, topic, difficulty, marks, source/year, explanation | `id` | `subject_id` → `subjects.id`; `created_by`/`on_behalf_of` → `users.id` | `question_text` NOT NULL; `is_approved` gate; `is_active`, `version_no` (cloud) |
| 9 | `question_options` | ✅ | A–E options per question, correctness flag | `id` | `question_id` → `questions.id` ON DELETE CASCADE | UNIQUE(question_id, option_label); correct-answer key lives here |
| 10 | `question_attachments` | ☁️ | Images/audio/diagrams attached to questions | `id` | `question_id` → `questions.id` | Storage-backed (Supabase bucket) |
| 11 | `question_versions` | ☁️ | Question edit history (versioning requirement) | `id` | `question_id` → `questions.id` | Snapshot of prior text/options on each revision |
| 12 | `teacher_imports` | ☁️ | Bulk teacher CSV import runs (validation + audit) | `id` | `imported_by` → `users.id` | Records per-row status |
| 13 | `formula_sheets` | ✅ | Per-subject LaTeX formula sheet shown in exams | `id` | `subject_id` → `subjects.id` | `is_active` |
| 14 | `exams` | ✅ | Exam definitions: subject, class, arm, term, session, duration, schedule, limits | `id` | `subject_id` → `subjects.id`; `created_by` → `users.id` | `is_practice`, **`is_mock`** (v1.0, never recorded officially), `fee_gate`, `negative_marking`, `attempt_limit`, `is_active`, `pass_mark`, start/end window; practice & mock excluded by `v_official_exams` view |
| 15 | `exam_questions` | ✅ | Link table: which questions belong to which exam | `id` | `exam_id` → `exams.id`; `question_id` → `questions.id` | UNIQUE(exam_id, question_id) |
| 16 | `exam_attempts` | ✅ | One row per student exam run: start/end, variant, strikes, malpractice log, status | `id` | `exam_id` → `exams.id`; `student_id` → `users.id` | Statuses: IN_PROGRESS / SUBMITTED / MALPRACTICE / PRACTICE_SUBMITTED / **MOCK_SUBMITTED**; `strike_count`, `pc_name`, `ip_address`, autosave `time_remaining` |
| 17 | `attempt_answers` | ✅ | Student’s per-question selections during an attempt (autosave + grading source) | `id` | `attempt_id` → `exam_attempts.id`; `question_id` → `questions.id` | Answer snapshot independent of later question edits |
| 18 | `results` | ✅ | Official exam results only (never practice/mock rows) | `id` | `attempt_id` → `exam_attempts.id`; `student_id` → `users.id`; `exam_id` → `exams.id` | `score`, `total_questions`, `correct_answers`, `percentage`, `published`, `created_at`; feed positions, report cards, transcripts |
| 19 | `ca_scores` | ✅ | Continuous assessment (CA1/CA2, exam, totals, grade) per student-subject-term | `id` | `student_id` → `users.id`; `subject_id` → `subjects.id` | Weighting/grade boundaries from grading config; used by broadsheets |
| 20 | `result_pins` | ✅ | PINs issued for printed/legacy result slips (if used beyond profile PIN) | `id` | `student_id` → `users.id`; `result_id` → `results.id` | Hash/state lifecycle |
| 21 | `result_appeals` | ✅ | Student appeals on results with review workflow | `id` | `student_id` → `users.id`; `result_id` → `results.id` | Status: OPEN/UNDER_REVIEW/RESOLVED |
| 22 | `fees_ledger` | ✅ | Fee payment/status ledger for the fee gate | `id` | `student_id` → `users.id` | `fee_status`/amounts; eligibility checked at exam start |
| 23 | `school_classes`→(`id_cards`) | — | (see 5) | | | |
| 23b | `id_cards` | ☁️ | Student/staff ID-card issuance records | `id` | `user_id` → `users.id` | PDF+QR generation records |
| 24 | `announcements` | ✅ | School announcements (status-gated) | `id` | `created_by` → `users.id` | Status: DRAFT/APPROVED/ARCHIVED |
| 25 | `announcement_reads` | ☁️ | Read-receipts per user/announcement | `id` | `announcement_id`; `user_id` | UNIQUE(announcement_id, user_id) |
| 26 | `study_materials` | ✅ | Teacher-shared study resources (title-gated, subject-linked) | `id` | `subject_id` → `subjects.id`; `uploaded_by` → `users.id` | Approval/status field |
| 27 | `messages` | ✅ | Internal messaging (staff/student DM settings enforced) | `id` | `sender_id`/`recipient_id` → `users.id` | DM policy config-driven |
| 28 | `friendships` | ✅ | Social graph for messaging | `id` | `user_a`/`user_b` → `users.id` | UNIQUE pair |
| 29 | `user_profiles` | ✅ | Extended user profile (avatar, bio, social prefs) | `id` | `user_id` → `users.id` | 1:1 with users |
| 30 | `notification_queue` | ✅ | Outbound notification/email queue with retry state | `id` | `user_id` → `users.id` | `status` PENDING/SENT/FAILED; retried by SyncService-style loop |
| 31 | `audit_logs` | ✅ | Immutable audit trail: action, entity, details, PC/IP, actor | `id` | `user_id` → `users.id` | Written for login, register, arm moves, proctor strikes, MALPRACTICE_LOCKOUT, backup/restore, ACADEMIC_CALENDAR_UPDATE… |
| 32 | `backup_logs` | ✅ | Backup/restore history: type, file, size, SHA-256 checksum | `id` | `created_by` → `users.id` | Excluded from backup payloads (bookkeeping) |
| 33 | `sync_queue` | ✅ | Offline→cloud sync replay (idempotent, dedup) | `id` | entity refs by kind+key | Excluded from backup payloads |

**Views / functions (cloud):** `v_official_exams` (excludes practice+mock),
`staff_check`, `staff_recent_results`, `staff_broadsheet`, `staff_subjects`,
`staff_live_room`, `staff_upload_questions`, `parent_lookup_results` — all
SECURITY DEFINER with per-call credential checks.

**Business rules enforced at DB level (representative):** UNIQUE email /
admission_no / result_pin / subject_code; role CHECK (cloud); question-option
UNIQUE; exam-question UNIQUE; CA/exam/result aggregates exclude
practice & mock through query filters and the `v_official_exams` view; mock
attempts carry `MOCK_SUBMITTED` so positions/report cards/transcripts can
never count them.

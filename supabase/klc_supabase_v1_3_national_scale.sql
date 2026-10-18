-- KLC CBT Suite v1.3 - national-scale flow & integrity fixes (idempotent)
-- Found in the 2026-10 full cross-check:
--  1. exam_attempts UNIQUE(exam_id, student_id) made attempt_limit>1 and
--     crash-recovery re-entry impossible on the cloud. Attempt limits are
--     now enforced in the app (AttemptPolicy + ExamController count of
--     submitted sittings); the DB must allow multiple attempt rows.
--  2. exam_attempts status CHECK rejected 'MOCK_SUBMITTED', so every mock
--     exam submission failed on the cloud. Full status set restored.
--  3. Hot-path indexes for national volume (live monitor, attempt-limit
--     counting, broadsheets, question picking, audit review).
ALTER TABLE public.exam_attempts DROP CONSTRAINT IF EXISTS exam_attempts_exam_id_student_id_key;
ALTER TABLE public.exam_attempts DROP CONSTRAINT IF EXISTS exam_attempts_status_check;
ALTER TABLE public.exam_attempts ADD CONSTRAINT exam_attempts_status_check
  CHECK (status IN ('IN_PROGRESS','SUBMITTED','MALPRACTICE','TIMED_OUT',
                    'PRACTICE_SUBMITTED','MOCK_SUBMITTED'));

CREATE INDEX IF NOT EXISTS idx_attempts_exam_student_status ON public.exam_attempts(exam_id, student_id, status);
CREATE INDEX IF NOT EXISTS idx_attempts_status_started      ON public.exam_attempts(status, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_results_exam                 ON public.results(exam_id);
CREATE INDEX IF NOT EXISTS idx_results_student_created      ON public.results(student_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ca_class_term_session        ON public.ca_scores(class_level, term, session);
CREATE INDEX IF NOT EXISTS idx_questions_pick               ON public.questions(subject_id, class_level, is_approved);
CREATE INDEX IF NOT EXISTS idx_students_class_status        ON public.student_profiles(class_level, status);
CREATE INDEX IF NOT EXISTS idx_audit_created                ON public.audit_logs(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_answers_attempt              ON public.attempt_answers(attempt_id);

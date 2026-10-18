-- KLC CBT Suite v1.2 completion migration (idempotent)
-- Fixes offline/online parity gaps found in the 2026-10 completion audit:
--  * student_profiles.updated_at — ClassManager arm moves UPDATE this column;
--    it existed on neither the H2 cache nor the cloud, so the UPDATE failed.
--  * question_versions — question edit history (was referenced in docs as
--    cloud-only but never actually created anywhere).
ALTER TABLE public.student_profiles ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT now();

CREATE TABLE IF NOT EXISTS public.question_versions (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    question_id   UUID REFERENCES public.questions(id) ON DELETE CASCADE,
    version_no    INT DEFAULT 1,
    question_text TEXT,
    changed_by    UUID,
    change_note   VARCHAR(300),
    created_at    TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_question_versions_q ON public.question_versions(question_id, version_no);
ALTER TABLE public.question_versions ENABLE ROW LEVEL SECURITY;

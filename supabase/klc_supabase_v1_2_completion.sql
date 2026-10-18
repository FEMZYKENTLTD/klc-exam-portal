-- KLC CBT Suite v1.2 completion migration (idempotent)
-- Fixes offline/online parity gaps found in the 2026-10 completion audit:
--  * student_profiles.updated_at — ClassManager arm moves UPDATE this column;
--    it existed on neither the H2 cache nor the cloud, so the UPDATE failed.
--  * question_versions — canonical shape is the v6_3 one (version,
--    explanation, edited_by, edited_at); the additive columns below converge
--    deployments that created the table from an early v1.2 draft
--    (version_no/changed_by/change_note/created_at) instead of failing.
ALTER TABLE public.student_profiles ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT now();

CREATE TABLE IF NOT EXISTS public.question_versions (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    question_id   UUID REFERENCES public.questions(id) ON DELETE CASCADE,
    version       INT NOT NULL DEFAULT 1,
    question_text TEXT,
    explanation   TEXT,
    edited_by     UUID REFERENCES public.users(id),
    edited_at     TIMESTAMPTZ DEFAULT now()
);
ALTER TABLE public.question_versions ADD COLUMN IF NOT EXISTS version INT DEFAULT 1;
ALTER TABLE public.question_versions ADD COLUMN IF NOT EXISTS explanation TEXT;
ALTER TABLE public.question_versions ADD COLUMN IF NOT EXISTS edited_by UUID;
ALTER TABLE public.question_versions ADD COLUMN IF NOT EXISTS edited_at TIMESTAMPTZ DEFAULT now();
ALTER TABLE public.question_versions ADD COLUMN IF NOT EXISTS version_no INT;
ALTER TABLE public.question_versions ADD COLUMN IF NOT EXISTS changed_by UUID;
ALTER TABLE public.question_versions ADD COLUMN IF NOT EXISTS change_note VARCHAR(300);
ALTER TABLE public.question_versions ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ DEFAULT now();
CREATE INDEX IF NOT EXISTS idx_qv_question ON public.question_versions(question_id);
CREATE INDEX IF NOT EXISTS idx_question_versions_q ON public.question_versions(question_id, version);
ALTER TABLE public.question_versions ENABLE ROW LEVEL SECURITY;

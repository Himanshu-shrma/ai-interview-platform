-- ── V6: Extend questions table for AI-generated content ──────────────────────

ALTER TABLE questions
    ADD COLUMN source             VARCHAR(20)  NOT NULL DEFAULT 'AI_GENERATED',
    ADD COLUMN deleted_at         TIMESTAMPTZ  NULL,
    ADD COLUMN generation_params  JSONB        NULL,
    ADD COLUMN space_complexity   VARCHAR(20)  NULL,
    ADD COLUMN time_complexity    VARCHAR(20)  NULL,
    ADD COLUMN evaluation_criteria JSONB       NULL,
    ADD COLUMN slug               VARCHAR(255) NULL,
    ADD COLUMN interview_category VARCHAR(30)  NOT NULL DEFAULT 'CODING';

-- Unique index on slug (allows NULLs — only non-null slugs must be unique)
CREATE UNIQUE INDEX idx_questions_slug ON questions(slug) WHERE slug IS NOT NULL;

-- Index for the random-selection query used by QuestionService
CREATE INDEX idx_questions_category_difficulty ON questions(interview_category, difficulty)
    WHERE deleted_at IS NULL;

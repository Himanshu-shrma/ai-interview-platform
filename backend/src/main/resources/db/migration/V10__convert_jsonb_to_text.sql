-- V10: Convert all JSONB columns to TEXT for R2DBC compatibility.
-- R2DBC PostgreSQL driver sends Kotlin String as VARCHAR, which Postgres
-- rejects for JSONB columns. TEXT stores the same JSON strings without
-- requiring explicit casts.

-- ── questions ────────────────────────────────────────────────────────────────
ALTER TABLE questions ALTER COLUMN examples TYPE TEXT;
ALTER TABLE questions ALTER COLUMN test_cases TYPE TEXT;
ALTER TABLE questions ALTER COLUMN solution_hints TYPE TEXT;
ALTER TABLE questions ALTER COLUMN follow_up_prompts TYPE TEXT;
ALTER TABLE questions ALTER COLUMN generation_params TYPE TEXT;
ALTER TABLE questions ALTER COLUMN evaluation_criteria TYPE TEXT;

-- ── interview_sessions ──────────────────────────────────────────────────────
ALTER TABLE interview_sessions ALTER COLUMN config DROP DEFAULT;
ALTER TABLE interview_sessions ALTER COLUMN config TYPE TEXT;
ALTER TABLE interview_sessions ALTER COLUMN config SET DEFAULT '{}';

-- ── conversation_messages ───────────────────────────────────────────────────
ALTER TABLE conversation_messages ALTER COLUMN metadata TYPE TEXT;

-- ── code_submissions ────────────────────────────────────────────────────────
ALTER TABLE code_submissions ALTER COLUMN test_results TYPE TEXT;

-- ── evaluation_reports ──────────────────────────────────────────────────────
ALTER TABLE evaluation_reports ALTER COLUMN strengths TYPE TEXT;
ALTER TABLE evaluation_reports ALTER COLUMN weaknesses TYPE TEXT;
ALTER TABLE evaluation_reports ALTER COLUMN suggestions TYPE TEXT;
ALTER TABLE evaluation_reports ALTER COLUMN dimension_feedback TYPE TEXT;

-- ── interview_templates ─────────────────────────────────────────────────────
ALTER TABLE interview_templates ALTER COLUMN config DROP DEFAULT;
ALTER TABLE interview_templates ALTER COLUMN config TYPE TEXT;
ALTER TABLE interview_templates ALTER COLUMN config SET DEFAULT '{}';

-- V12: Add interview stage tracking + code templates for questions

-- Track current interview stage in DB (persisted on every stage transition)
ALTER TABLE interview_sessions
    ADD COLUMN IF NOT EXISTS current_stage VARCHAR(30) DEFAULT 'SMALL_TALK';

-- Code templates per language (generated alongside questions)
ALTER TABLE questions
    ADD COLUMN IF NOT EXISTS code_templates TEXT DEFAULT '{}';

-- Function signature metadata (for template generation)
ALTER TABLE questions
    ADD COLUMN IF NOT EXISTS function_signature TEXT DEFAULT '{}';

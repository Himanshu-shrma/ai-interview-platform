-- TASK-P1-01: Onboarding wizard — user answers + completion flag
ALTER TABLE users ADD COLUMN IF NOT EXISTS onboarding_completed BOOLEAN DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS onboarding_answers  TEXT;  -- JSON stored as TEXT (R2DBC limitation)

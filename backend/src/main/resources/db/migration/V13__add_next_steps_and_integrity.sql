-- V13: Add next_steps column to evaluation_reports and integrity_signals to interview_sessions
ALTER TABLE evaluation_reports ADD COLUMN IF NOT EXISTS next_steps TEXT;
ALTER TABLE interview_sessions ADD COLUMN IF NOT EXISTS integrity_signals TEXT;

-- V20: Add feedback column for post-session outcome reporting (NPS, fairness, difficulty)
ALTER TABLE interview_sessions ADD COLUMN IF NOT EXISTS feedback TEXT;

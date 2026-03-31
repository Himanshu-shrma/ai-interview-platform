-- V15: Widen tight VARCHAR columns that could overflow with AI-generated content
ALTER TABLE questions ALTER COLUMN space_complexity TYPE VARCHAR(100);
ALTER TABLE questions ALTER COLUMN time_complexity TYPE VARCHAR(100);
ALTER TABLE questions ALTER COLUMN source TYPE VARCHAR(50);
ALTER TABLE questions ALTER COLUMN interview_category TYPE VARCHAR(50);
ALTER TABLE interview_sessions ALTER COLUMN current_stage TYPE VARCHAR(50);

-- V14: Add brain-derived evaluation columns
ALTER TABLE evaluation_reports
    ADD COLUMN IF NOT EXISTS anxiety_level DECIMAL(3,2) DEFAULT 0,
    ADD COLUMN IF NOT EXISTS anxiety_adjustment_applied DECIMAL(3,2) DEFAULT 0,
    ADD COLUMN IF NOT EXISTS initiative_score DECIMAL(4,2),
    ADD COLUMN IF NOT EXISTS learning_agility_score DECIMAL(4,2),
    ADD COLUMN IF NOT EXISTS research_notes TEXT;

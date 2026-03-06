-- ── V7: Extend evaluation_reports with feedback fields ───────────────────────

ALTER TABLE evaluation_reports
    ADD COLUMN IF NOT EXISTS dimension_feedback JSONB,
    ADD COLUMN IF NOT EXISTS hints_used         INT          NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS completed_at       TIMESTAMPTZ;

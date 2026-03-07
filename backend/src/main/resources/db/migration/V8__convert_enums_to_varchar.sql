-- ── V8: Convert Postgres enum columns to VARCHAR for R2DBC compatibility ─────
-- R2DBC does not auto-cast String → Postgres enum, causing BadSqlGrammarException.
-- VARCHAR with CHECK constraints gives the same validation without driver issues.

-- organizations.type (org_type)
ALTER TABLE organizations ALTER COLUMN type TYPE VARCHAR(50) USING type::text;
ALTER TABLE organizations ALTER COLUMN type SET DEFAULT 'PERSONAL';

-- interview_templates.type (interview_type)
ALTER TABLE interview_templates ALTER COLUMN type TYPE VARCHAR(50) USING type::text;

-- interview_sessions.status (session_status)
ALTER TABLE interview_sessions ALTER COLUMN status TYPE VARCHAR(50) USING status::text;
ALTER TABLE interview_sessions ALTER COLUMN status SET DEFAULT 'PENDING';

-- interview_sessions.type (interview_type)
ALTER TABLE interview_sessions ALTER COLUMN type TYPE VARCHAR(50) USING type::text;

-- questions.type (interview_type) — if exists
DO $$ BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'questions' AND column_name = 'type'
        AND udt_name = 'interview_type'
    ) THEN
        ALTER TABLE questions ALTER COLUMN type TYPE VARCHAR(50) USING type::text;
    END IF;
END $$;

-- Drop the enum types (no longer needed)
DROP TYPE IF EXISTS org_type;
DROP TYPE IF EXISTS session_status;
DROP TYPE IF EXISTS interview_type;

-- ── V9: Convert remaining Postgres enum columns to VARCHAR for R2DBC ─────────
-- Must drop enum-typed defaults BEFORE altering column type, then re-set defaults.

-- users.role (user_role)
ALTER TABLE users ALTER COLUMN role DROP DEFAULT;
ALTER TABLE users ALTER COLUMN role TYPE VARCHAR(50) USING role::text;
ALTER TABLE users ALTER COLUMN role SET DEFAULT 'CANDIDATE';

-- org_invitations.role (user_role)
ALTER TABLE org_invitations ALTER COLUMN role DROP DEFAULT;
ALTER TABLE org_invitations ALTER COLUMN role TYPE VARCHAR(50) USING role::text;

-- interview_templates.difficulty (difficulty)
ALTER TABLE interview_templates ALTER COLUMN difficulty DROP DEFAULT;
ALTER TABLE interview_templates ALTER COLUMN difficulty TYPE VARCHAR(50) USING difficulty::text;

-- questions.difficulty (difficulty)
ALTER TABLE questions ALTER COLUMN difficulty DROP DEFAULT;
ALTER TABLE questions ALTER COLUMN difficulty TYPE VARCHAR(50) USING difficulty::text;

-- conversation_messages.role (message_role)
ALTER TABLE conversation_messages ALTER COLUMN role DROP DEFAULT;
ALTER TABLE conversation_messages ALTER COLUMN role TYPE VARCHAR(50) USING role::text;

-- code_submissions.status (submission_status)
ALTER TABLE code_submissions ALTER COLUMN status DROP DEFAULT;
ALTER TABLE code_submissions ALTER COLUMN status TYPE VARCHAR(50) USING status::text;

-- Drop the enum types (now safe — no dependencies)
DROP TYPE IF EXISTS user_role;
DROP TYPE IF EXISTS difficulty;
DROP TYPE IF EXISTS message_role;
DROP TYPE IF EXISTS submission_status;

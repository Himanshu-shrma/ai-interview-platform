-- V17: Add soft-delete support for GDPR data deletion
-- Allows candidates to request account deletion without hard-deleting immediately.
-- Soft-deleted records are hidden from all application queries.
-- A scheduled purge (UserDeletionService) hard-deletes records older than 365 days.

ALTER TABLE interview_sessions    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
ALTER TABLE conversation_messages ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
ALTER TABLE evaluation_reports    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
ALTER TABLE code_submissions      ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;

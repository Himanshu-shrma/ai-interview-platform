-- Add heartbeat tracking for session recovery and abandoned session detection
ALTER TABLE interview_sessions
    ADD COLUMN IF NOT EXISTS last_heartbeat TIMESTAMPTZ DEFAULT NOW();

-- Index for finding stale sessions efficiently
CREATE INDEX IF NOT EXISTS idx_sessions_heartbeat ON interview_sessions(last_heartbeat)
    WHERE status = 'ACTIVE';

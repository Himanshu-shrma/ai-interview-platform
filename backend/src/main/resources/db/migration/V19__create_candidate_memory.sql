-- V19: Cross-session candidate memory profiles + memory opt-out on users

CREATE TABLE IF NOT EXISTS candidate_memory_profiles (
    user_id               VARCHAR(255) PRIMARY KEY,
    session_count         INT          DEFAULT 0,
    -- Raw signal arrays (compute derived labels at read time — never store labels as VARCHAR)
    avg_score_per_dimension TEXT       DEFAULT '{}',   -- JSON: {"problem_solving": [6.5, 7.0]}
    avg_anxiety_per_session TEXT       DEFAULT '[]',   -- JSON: [0.7, 0.4, 0.5]
    questions_seen          TEXT       DEFAULT '[]',   -- JSON: ["uuid1", "uuid2"]
    dimension_trend         TEXT       DEFAULT '{}',   -- JSON: {"problem_solving": "IMPROVING"}
    last_updated            TIMESTAMP  DEFAULT NOW()
);

-- Memory opt-out flag on users (default: enabled)
ALTER TABLE users ADD COLUMN IF NOT EXISTS memory_enabled BOOLEAN DEFAULT TRUE;

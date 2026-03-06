-- ── V3: Interview Tables ───────────────────────────────────────────────────────

CREATE TYPE interview_type   AS ENUM ('DSA', 'CODING', 'SYSTEM_DESIGN', 'BEHAVIORAL');
CREATE TYPE session_status   AS ENUM ('PENDING', 'ACTIVE', 'COMPLETED', 'ABANDONED', 'EXPIRED');
CREATE TYPE difficulty       AS ENUM ('EASY', 'MEDIUM', 'HARD');
CREATE TYPE message_role     AS ENUM ('AI', 'CANDIDATE', 'SYSTEM');

-- ── Questions ─────────────────────────────────────────────────────────────────
CREATE TABLE questions (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    title               VARCHAR(255) NOT NULL,
    description         TEXT         NOT NULL,
    type                interview_type NOT NULL,
    difficulty          difficulty   NOT NULL,
    topic_tags          TEXT[],
    examples            JSONB,
    constraints         TEXT,
    test_cases          JSONB,
    solution_hints      JSONB,
    optimal_approach    TEXT,
    follow_up_prompts   JSONB,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_questions_type_difficulty ON questions(type, difficulty);

-- ── Interview Sessions ────────────────────────────────────────────────────────
CREATE TABLE interview_sessions (
    id           UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID           NOT NULL REFERENCES users(id),
    status       session_status NOT NULL DEFAULT 'PENDING',
    type         interview_type NOT NULL,
    config       JSONB          NOT NULL DEFAULT '{}',
    started_at   TIMESTAMPTZ,
    ended_at     TIMESTAMPTZ,
    duration_secs INT,
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sessions_user_status ON interview_sessions(user_id, status);

-- ── Session Questions (join table + per-question progress) ────────────────────
CREATE TABLE session_questions (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id    UUID        NOT NULL REFERENCES interview_sessions(id),
    question_id   UUID        NOT NULL REFERENCES questions(id),
    order_index   INT         NOT NULL DEFAULT 0,
    final_code    TEXT,
    language_used VARCHAR(50),
    submitted_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_session_questions_session ON session_questions(session_id);

-- ── Conversation Messages ─────────────────────────────────────────────────────
CREATE TABLE conversation_messages (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID         NOT NULL REFERENCES interview_sessions(id),
    role       message_role NOT NULL,
    content    TEXT         NOT NULL,
    metadata   JSONB,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_messages_session_created ON conversation_messages(session_id, created_at);

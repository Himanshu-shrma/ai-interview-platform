-- ── V4: Code Submissions + Evaluation Reports ──────────────────────────────────

CREATE TYPE submission_status AS ENUM (
    'PENDING', 'RUNNING', 'ACCEPTED', 'WRONG_ANSWER',
    'TIME_LIMIT', 'RUNTIME_ERROR', 'COMPILE_ERROR'
);

-- ── Code Submissions ──────────────────────────────────────────────────────────
CREATE TABLE code_submissions (
    id                  UUID              PRIMARY KEY DEFAULT gen_random_uuid(),
    session_question_id UUID              NOT NULL REFERENCES session_questions(id),
    user_id             UUID              NOT NULL REFERENCES users(id),
    code                TEXT              NOT NULL,
    language            VARCHAR(50)       NOT NULL,
    status              submission_status NOT NULL DEFAULT 'PENDING',
    judge0_token        VARCHAR(255),
    test_results        JSONB,
    runtime_ms          INT,
    memory_kb           INT,
    submitted_at        TIMESTAMPTZ       NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_submissions_session_question ON code_submissions(session_question_id);
CREATE INDEX idx_submissions_user             ON code_submissions(user_id);

-- ── Evaluation Reports ────────────────────────────────────────────────────────
CREATE TABLE evaluation_reports (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id            UUID         NOT NULL UNIQUE REFERENCES interview_sessions(id),
    user_id               UUID         NOT NULL REFERENCES users(id),
    overall_score         DECIMAL(5,2),
    problem_solving_score DECIMAL(5,2),
    algorithm_score       DECIMAL(5,2),
    code_quality_score    DECIMAL(5,2),
    communication_score   DECIMAL(5,2),
    efficiency_score      DECIMAL(5,2),
    testing_score         DECIMAL(5,2),
    strengths             JSONB,
    weaknesses            JSONB,
    suggestions           JSONB,
    narrative_summary     TEXT,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_reports_user ON evaluation_reports(user_id);

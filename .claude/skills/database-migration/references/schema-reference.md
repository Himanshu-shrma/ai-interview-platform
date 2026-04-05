# Schema Reference — All Tables, Columns, Indexes

## organizations
```sql
CREATE TABLE organizations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    type VARCHAR(50) DEFAULT 'COMPANY',
    plan VARCHAR(50) DEFAULT 'FREE',
    seats_limit INT DEFAULT 5,
    created_at TIMESTAMP DEFAULT NOW()
);
```

## users
```sql
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID REFERENCES organizations(id),
    clerk_user_id VARCHAR(255) UNIQUE NOT NULL,
    email VARCHAR(255) NOT NULL,
    full_name VARCHAR(255),
    role VARCHAR(50) DEFAULT 'MEMBER',
    subscription_tier VARCHAR(50) DEFAULT 'FREE',
    created_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_users_clerk_id ON users(clerk_user_id);
CREATE INDEX idx_users_org_id ON users(org_id);
```

## questions
```sql
CREATE TABLE questions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title VARCHAR(500) NOT NULL,
    description TEXT NOT NULL,
    type VARCHAR(50),
    difficulty VARCHAR(50) DEFAULT 'MEDIUM',
    interview_category VARCHAR(50) DEFAULT 'CODING',
    test_cases TEXT,              -- JSON array: [{input, expectedOutput}]
    solution_hints TEXT,          -- JSON array
    optimal_approach TEXT,
    evaluation_criteria TEXT,     -- JSON
    code_templates TEXT,          -- JSON: {python: "...", java: "..."}
    time_complexity VARCHAR(100),
    space_complexity VARCHAR(100), -- widened in V15
    topic_tags TEXT,              -- JSON array
    slug VARCHAR(500),
    constraints_text TEXT,
    examples TEXT,
    deleted_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_questions_category ON questions(interview_category);
```

## interview_sessions
```sql
CREATE TABLE interview_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    type VARCHAR(50),
    config TEXT,                  -- JSON: InterviewConfig
    started_at TIMESTAMP WITH TIME ZONE,
    ended_at TIMESTAMP WITH TIME ZONE,
    duration_secs INT,
    last_heartbeat TIMESTAMP WITH TIME ZONE,  -- V11
    interview_stage VARCHAR(50),              -- V12
    integrity_signals TEXT,                    -- V13 JSON
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
CREATE INDEX idx_sessions_user ON interview_sessions(user_id);
CREATE INDEX idx_sessions_status ON interview_sessions(status);
```

## session_questions
```sql
CREATE TABLE session_questions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES interview_sessions(id) ON DELETE CASCADE,
    question_id UUID NOT NULL REFERENCES questions(id),
    order_index INT NOT NULL DEFAULT 0,
    final_code TEXT,
    language_used VARCHAR(50),
    submitted_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_sq_session ON session_questions(session_id);
```

## conversation_messages
```sql
CREATE TABLE conversation_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES interview_sessions(id) ON DELETE CASCADE,
    role VARCHAR(50) NOT NULL,    -- AI, CANDIDATE, SYSTEM
    content TEXT NOT NULL,
    metadata TEXT,                -- JSON
    created_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_messages_session ON conversation_messages(session_id);
```

## code_submissions
```sql
CREATE TABLE code_submissions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_question_id UUID NOT NULL REFERENCES session_questions(id),
    user_id UUID NOT NULL REFERENCES users(id),
    code TEXT NOT NULL,
    language VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    judge0_token VARCHAR(255),
    test_results TEXT,            -- JSON array
    runtime_ms INT,
    submitted_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_submissions_sq ON code_submissions(session_question_id);
```

## evaluation_reports
```sql
CREATE TABLE evaluation_reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES interview_sessions(id),
    user_id UUID NOT NULL REFERENCES users(id),
    overall_score NUMERIC(4,2),
    problem_solving_score NUMERIC(4,2),
    algorithm_score NUMERIC(4,2),
    code_quality_score NUMERIC(4,2),
    communication_score NUMERIC(4,2),
    efficiency_score NUMERIC(4,2),
    testing_score NUMERIC(4,2),
    initiative_score NUMERIC(4,2),       -- V14
    learning_agility_score NUMERIC(4,2), -- V14
    anxiety_level NUMERIC(3,2),          -- V14
    anxiety_adjustment_applied BOOLEAN DEFAULT FALSE, -- V14
    strengths TEXT,               -- JSON array
    weaknesses TEXT,              -- JSON array
    suggestions TEXT,             -- JSON array
    narrative_summary TEXT,
    dimension_feedback TEXT,      -- JSON map
    hints_used INT DEFAULT 0,
    next_steps TEXT,              -- V13 JSON array
    completed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_reports_session ON evaluation_reports(session_id);
CREATE INDEX idx_reports_user ON evaluation_reports(user_id);
```

## interview_templates
```sql
CREATE TABLE interview_templates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    config TEXT,                  -- JSON: InterviewConfig
    created_at TIMESTAMP DEFAULT NOW()
);
```

## org_invitations
```sql
CREATE TABLE org_invitations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL REFERENCES organizations(id),
    email VARCHAR(255) NOT NULL,
    role VARCHAR(50) DEFAULT 'MEMBER',
    status VARCHAR(50) DEFAULT 'PENDING',
    created_at TIMESTAMP DEFAULT NOW()
);
```

## Redis Keys
| Pattern | TTL | Service |
|---------|-----|---------|
| `brain:{sessionId}` | 3h | BrainService |
| `interview:session:{id}:memory` | 2h | RedisMemoryService |
| `user:clerk:{clerkId}` | 5min | UserBootstrapService |
| `ratelimit:{userId}:{minute}` | 2min | RateLimitFilter |
| `usage:{userId}:interviews:{YYYY-MM}` | 35d | UsageLimitService |

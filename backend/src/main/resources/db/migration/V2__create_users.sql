-- ── V2: Users ─────────────────────────────────────────────────────────────────

CREATE TYPE user_role AS ENUM ('CANDIDATE', 'RECRUITER', 'ADMIN');

CREATE TABLE users (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id            UUID        NOT NULL REFERENCES organizations(id),
    clerk_user_id     VARCHAR(255) NOT NULL UNIQUE,
    email             VARCHAR(255) NOT NULL,
    full_name         VARCHAR(255),
    role              user_role   NOT NULL DEFAULT 'CANDIDATE',
    subscription_tier VARCHAR(50) NOT NULL DEFAULT 'FREE',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_clerk_user_id ON users(clerk_user_id);
CREATE INDEX idx_users_org_id        ON users(org_id);

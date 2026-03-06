-- ── V5: Misc — Interview Templates + Org Invitations (schema only) ─────────────

-- ── Interview Templates ───────────────────────────────────────────────────────
CREATE TABLE interview_templates (
    id         UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(255)   NOT NULL,
    type       interview_type NOT NULL,
    difficulty difficulty     NOT NULL,
    config     JSONB          NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

-- ── Org Invitations ───────────────────────────────────────────────────────────
CREATE TABLE org_invitations (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      UUID        NOT NULL REFERENCES organizations(id),
    email       VARCHAR(255) NOT NULL,
    role        user_role   NOT NULL DEFAULT 'CANDIDATE',
    token       VARCHAR(255) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_org_invitations_org  ON org_invitations(org_id);
CREATE INDEX idx_org_invitations_token ON org_invitations(token);

-- ── V1: Organizations ─────────────────────────────────────────────────────────

CREATE TYPE org_type AS ENUM ('PERSONAL', 'COMPANY', 'UNIVERSITY');

CREATE TABLE organizations (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL,
    type        org_type    NOT NULL DEFAULT 'PERSONAL',
    plan        VARCHAR(50) NOT NULL DEFAULT 'FREE',
    seats_limit INT         NOT NULL DEFAULT 1,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

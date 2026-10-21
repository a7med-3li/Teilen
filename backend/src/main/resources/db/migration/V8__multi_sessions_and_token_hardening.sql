-- V8: Multi-Session Rooms & Token Hardening

-- 1. Create sessions table
CREATE TABLE sessions (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    owner_user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    invite_code_hash VARCHAR(64) UNIQUE,
    is_personal BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_sessions_owner ON sessions (owner_user_id);
CREATE INDEX idx_sessions_invite ON sessions (invite_code_hash);

-- 2. Create session_members table
CREATE TABLE session_members (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL, -- OWNER | MEMBER
    encrypted_session_key TEXT,
    joined_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_session_member UNIQUE (session_id, user_id)
);

CREATE INDEX idx_session_members_user ON session_members (user_id);
CREATE INDEX idx_session_members_session ON session_members (session_id);

-- 3. Backfill Personal session for each existing user
INSERT INTO sessions (id, name, owner_user_id, invite_code_hash, is_personal, created_at)
SELECT gen_random_uuid(), 'Personal Vault', u.id, NULL, TRUE, u.created_at
FROM users u;

-- Add each user as OWNER of their personal session
INSERT INTO session_members (id, session_id, user_id, role, encrypted_session_key, joined_at)
SELECT gen_random_uuid(), s.id, s.owner_user_id, 'OWNER', NULL, s.created_at
FROM sessions s
WHERE s.is_personal = TRUE;

-- 4. Add session_id to share_items
ALTER TABLE share_items
    ADD COLUMN session_id UUID REFERENCES sessions (id) ON DELETE CASCADE;

-- Backfill existing share_items to point to the user's personal session
UPDATE share_items si
SET session_id = s.id
FROM sessions s
WHERE s.owner_user_id = si.user_id AND s.is_personal = TRUE;

-- For any leftover share items where session_id is still null, default to user's personal session if any, or create one
UPDATE share_items si
SET session_id = (SELECT s.id FROM sessions s WHERE s.owner_user_id = si.user_id AND s.is_personal = TRUE LIMIT 1)
WHERE si.session_id IS NULL;

-- Enforce NOT NULL on session_id
ALTER TABLE share_items
    ALTER COLUMN session_id SET NOT NULL;

CREATE INDEX idx_share_items_session_created ON share_items (session_id, created_at DESC);

-- 5. Add device security columns for Token Hardening & DPoP
ALTER TABLE devices
    ADD COLUMN device_public_key VARCHAR(200),
    ADD COLUMN ip_address VARCHAR(64),
    ADD COLUMN platform VARCHAR(160);

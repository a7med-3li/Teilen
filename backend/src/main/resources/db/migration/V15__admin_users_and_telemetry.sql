CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 1. Dedicated table for Admin accounts (strictly separate from app users; NO registration API)
CREATE TABLE IF NOT EXISTS admin_users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_login_at TIMESTAMPTZ
);

-- 2. Dedicated table for Admin active sessions
CREATE TABLE IF NOT EXISTS admin_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id UUID NOT NULL REFERENCES admin_users(id) ON DELETE CASCADE,
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 3. Privacy-safe historical & live activity counter table (tracks lifetime totals even after ephemeral items self-destruct)
CREATE TABLE IF NOT EXISTS telemetry_events (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(48) NOT NULL, -- 'ITEM_SHARED', 'DEVICE_PAIRED', 'VAULT_MERGED', 'SESSION_CREATED', 'LAN_P2P_COMPLETED'
    item_type VARCHAR(24),           -- 'TEXT', 'LINK', 'IMAGE', 'FILE', 'AUDIO', 'PDF' (nullable)
    payload_bytes BIGINT DEFAULT 0,  -- Size of encrypted blob in bytes (0 for text)
    ttl_seconds INT,                 -- Chosen expiration timer (e.g., 600, 1800, 3600, 86400)
    platform VARCHAR(24),            -- 'ANDROID', 'WEB'
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_telemetry_created_at ON telemetry_events(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_telemetry_event_type ON telemetry_events(event_type);

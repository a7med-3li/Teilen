-- V12: Add client_fingerprint to devices for deduplicated silent provisioning
ALTER TABLE devices
    ADD COLUMN IF NOT EXISTS client_fingerprint VARCHAR(100);

CREATE INDEX IF NOT EXISTS idx_devices_user_fingerprint ON devices (user_id, client_fingerprint);
CREATE INDEX IF NOT EXISTS idx_devices_user_name_type ON devices (user_id, name, device_type);

-- Ensure creator_user_id is available on pairing_requests (mirrored to user_id if already present)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'pairing_requests' AND column_name = 'creator_user_id') THEN
        ALTER TABLE pairing_requests ADD COLUMN creator_user_id UUID REFERENCES users (id) ON DELETE CASCADE;
        UPDATE pairing_requests SET creator_user_id = user_id WHERE creator_user_id IS NULL AND user_id IS NOT NULL;
    END IF;
END $$;

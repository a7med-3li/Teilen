-- V10: Add host invite support to pairing_requests
ALTER TABLE pairing_requests
    ADD COLUMN user_id UUID REFERENCES users (id) ON DELETE CASCADE,
    ADD COLUMN is_host_invite BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE pairing_requests
    ALTER COLUMN key_package TYPE VARCHAR(16000);

CREATE INDEX idx_pairing_requests_user_id ON pairing_requests (user_id);

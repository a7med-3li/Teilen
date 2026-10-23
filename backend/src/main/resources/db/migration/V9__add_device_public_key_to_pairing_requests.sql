-- V9: Add device_public_key to pairing_requests for persistent DPoP identity during pairing
ALTER TABLE pairing_requests
    ADD COLUMN device_public_key VARCHAR(200);

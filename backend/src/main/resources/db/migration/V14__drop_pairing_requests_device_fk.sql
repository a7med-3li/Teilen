-- V14: Make the pairing_requests -> devices foreign key safe to delete a device.
--
-- A pairing request may reference a device that is later removed by the vault device
-- deduplication. With the old NO ACTION constraint a stale device_id made the pending
-- Hibernate UPDATE fail, rolling back the whole pairing. ON DELETE SET NULL keeps the
-- request valid and also prevents the crash if any device is removed after approval.

-- Clear any orphaned references left behind by earlier failed runs before validating.
UPDATE pairing_requests pr
SET device_id = NULL
WHERE pr.device_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM devices d WHERE d.id = pr.device_id);

ALTER TABLE pairing_requests
    DROP CONSTRAINT IF EXISTS pairing_requests_device_id_fkey;

ALTER TABLE pairing_requests
    ADD CONSTRAINT pairing_requests_device_id_fkey
    FOREIGN KEY (device_id) REFERENCES devices (id) ON DELETE SET NULL;

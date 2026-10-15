-- v6: phone + password authentication.
-- Users can now authenticate with a password instead of relying solely on device-to-device pairing.
alter table users
    add column password_hash varchar(255);

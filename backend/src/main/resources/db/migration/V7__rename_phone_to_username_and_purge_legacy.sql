-- 1. Delete dependent records belonging to legacy passwordless accounts
DELETE FROM pairing_requests
WHERE device_id IN (
    SELECT d.id FROM devices d
    JOIN users u ON d.user_id = u.id
    WHERE u.password_hash IS NULL OR TRIM(u.password_hash) = ''
);

DELETE FROM push_subscriptions
WHERE user_id IN (
    SELECT id FROM users WHERE password_hash IS NULL OR TRIM(password_hash) = ''
);

DELETE FROM share_items
WHERE user_id IN (
    SELECT id FROM users WHERE password_hash IS NULL OR TRIM(password_hash) = ''
);

DELETE FROM devices
WHERE user_id IN (
    SELECT id FROM users WHERE password_hash IS NULL OR TRIM(password_hash) = ''
);

-- 2. Delete all legacy accounts that do not have a password
DELETE FROM users
WHERE password_hash IS NULL OR TRIM(password_hash) = '';

-- 3. Sanitize any remaining usernames (strip non-alphanumeric characters if any existed from old phone numbers like '+')
UPDATE users
SET phone = REGEXP_REPLACE(phone, '[^a-zA-Z0-9]', '', 'g');

-- 4. Rename column `phone` to `username` and enforce NOT NULL on both `username` and `password_hash`
ALTER TABLE users RENAME COLUMN phone TO username;
ALTER TABLE users ALTER COLUMN username SET NOT NULL;
ALTER TABLE users ALTER COLUMN password_hash SET NOT NULL;

-- 5. Enforce case-insensitive uniqueness on username so 'Ahmed' and 'ahmed' cannot collide
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_username_lower ON users (LOWER(username));

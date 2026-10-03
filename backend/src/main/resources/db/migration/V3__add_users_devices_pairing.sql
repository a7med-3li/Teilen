-- v3: accounts. An item belongs to somebody now, so a feed is private to its owner.
--
-- Every row from v1/v2 is dropped on the way in: they had no owner to point at and were all on a
-- timer anyway. Their blobs on disk stay behind (SQL cannot unlink files); the reaper only ever
-- deletes blobs it still has a row for, so they are harmless orphans — clear the directory once.
delete from share_items;

-- identity is a phone number. No OTP yet: creating an account proves nothing, so the first
-- device that claims a number owns it. Every later device has to be approved (see pairing_requests).
create table users
(
    id           uuid primary key,
    phone        varchar(32) not null unique,
    display_name varchar(80),
    created_at   timestamptz not null
);

-- one row per paired app: the phone that created the account, plus every browser and spare phone
create table devices
(
    id           uuid primary key,
    user_id      uuid not null references users (id) on delete cascade,
    device_type  varchar(16) not null, -- PHONE | WEB
    name         varchar(120) not null,
    -- sha-256 of the bearer token; the token itself is shown once and never stored
    token_hash   varchar(64) not null unique,
    created_at   timestamptz not null,
    last_seen_at timestamptz not null
);

create index idx_devices_user_id on devices (user_id);

-- a pairing in flight, RFC 8628 shaped: the device that wants to join asks for a code, one of the
-- devices already paired approves it, then the newcomer collects its token by polling.
create table pairing_requests
(
    id               uuid primary key,
    -- the newcomer's secret, used only for polling; 32 random bytes hashed
    device_code_hash varchar(64) not null unique,
    -- what the user scans or types, e.g. "K7PM-3XQD"; hashed so a database leak is not a code leak
    user_code_hash   varchar(64) not null unique,
    device_name      varchar(120) not null,
    device_type      varchar(16) not null, -- PHONE | WEB, decided by the newcomer
    platform         varchar(160),        -- user agent or platform, for the approval prompt
    status           varchar(16) not null, -- PENDING | APPROVED | DELIVERED | DENIED
    device_id        uuid references devices (id) on delete set null,
    -- the minted token, readable only until the newcomer collects it, then wiped
    token_value      varchar(200),
    created_at       timestamptz not null,
    expires_at       timestamptz not null,
    resolved_at      timestamptz
);

create index idx_pairing_requests_expires_at on pairing_requests (expires_at);

-- and finally: every item has an owner
alter table share_items
    add column user_id uuid not null references users (id) on delete cascade;

create index idx_share_items_user_created on share_items (user_id, created_at desc);

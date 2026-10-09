-- v4: carry an end-to-end encrypted account key across a pairing.
--
-- Nothing here is readable by this server. The newcomer publishes a throwaway X25519 public key
-- with its pairing request; the approving device wraps the account's master key to that key and
-- leaves the sealed bundle behind; the newcomer collects it exactly once, together with its token.
-- The columns are nullable because pairing still works without a key exchange — older apps simply
-- do not take part in it.

alter table pairing_requests
    -- the newcomer's single-use public key, base64; only ever relayed, never used here
    add column newcomer_public_key varchar(200),
    -- the approver's sealed master-key bundle (JSON: pk, nonce, wrapped key, signature), opaque
    add column key_package varchar(4000);

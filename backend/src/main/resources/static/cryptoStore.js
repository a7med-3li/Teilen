/*
 * Teilen's local secrets. Nothing here ever leaves the browser in the clear.
 *
 * Two things live in IndexedDB, and they are the whole zero-knowledge story:
 *
 *   - the account key (32 random bytes) that everything shared is sealed under
 *   - this device's Ed25519 identity, used later to sign the WebRTC handshake
 *
 * Pairing is the only moment a key travels, and even then it travels sealed: the newcomer offers a
 * throwaway X25519 public key, the approving device derives a wrapping key from the shared secret
 * and seals the account key to it. The server relays a blob it cannot read.
 *
 * Depends on the vendored libsodium UMD build, loaded before this file. No build step.
 */
window.TeilenCrypto = (() => {
    'use strict';

    const DB_NAME = 'teilen_sec_db';
    const STORE = 'keys';
    const MASTER = 'account_master_key';
    const IDENTITY = 'identity_ed25519';

    // the string the approving device signs over, so a relayed bundle cannot be re-pointed
    const SIGN_CONTEXT = 'teilen-pairing-v1';
    // domain separation for the key that wraps the account key, both sides must agree
    const HKDF_SALT = 'teilen-pairing';
    const HKDF_INFO = 'teilen-master-key';
    const SECRETBOX_NONCE = 24;

    const ready = (async () => {
        if (typeof sodium === 'undefined') {
            throw new Error('libsodium did not load');
        }
        await sodium.ready;
    })();

    // standard, padded base64 — the same bytes java.util.Base64 produces on the phone
    const b64 = (bytes) => sodium.to_base64(bytes, sodium.base64_variants.ORIGINAL);
    const unb64 = (text) => sodium.from_base64(text, sodium.base64_variants.ORIGINAL);

    /** user codes are written every which way; the signature is over the compact form */
    function compact(code) {
        return (code || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
    }

    // ---------- IndexedDB, one store, key/value ----------

    function openDb() {
        return new Promise((resolve, reject) => {
            const request = indexedDB.open(DB_NAME, 1);
            request.onupgradeneeded = () => request.result.createObjectStore(STORE);
            request.onsuccess = () => resolve(request.result);
            request.onerror = () => reject(request.error);
        });
    }

    async function idbGet(key) {
        const db = await openDb();
        return new Promise((resolve, reject) => {
            const request = db.transaction(STORE, 'readonly').objectStore(STORE).get(key);
            request.onsuccess = () => resolve(request.result || null);
            request.onerror = () => reject(request.error);
        });
    }

    async function idbPut(key, value) {
        const db = await openDb();
        return new Promise((resolve, reject) => {
            const tx = db.transaction(STORE, 'readwrite');
            tx.objectStore(STORE).put(value, key);
            tx.oncomplete = () => resolve();
            tx.onerror = () => reject(tx.error);
        });
    }

    // ---------- HKDF-SHA256 (RFC 5869), hand-rolled over libsodium's HMAC ----------
    //
    // libsodium-wrappers does not expose crypto_kdf_hkdf_*, and its one-shot HMAC insists on a
    // 32-byte key — the salt here is not 32 bytes, so the init/update/final form is used. This is
    // plain RFC 5869; the phone implements the same thing over javax.crypto.Mac.

    function hmacSha256(key, message) {
        const state = sodium.crypto_auth_hmacsha256_init(key);
        sodium.crypto_auth_hmacsha256_update(state, message);
        return sodium.crypto_auth_hmacsha256_final(state);
    }

    function hkdfSha256(ikm, salt, info, length) {
        const prk = hmacSha256(salt, ikm);
        const out = new Uint8Array(length);
        let block = new Uint8Array(0);
        let filled = 0;
        let counter = 1;
        while (filled < length) {
            const input = new Uint8Array(block.length + info.length + 1);
            input.set(block);
            input.set(info, block.length);
            input[input.length - 1] = counter;
            block = hmacSha256(prk, input);
            const take = Math.min(block.length, length - filled);
            out.set(block.subarray(0, take), filled);
            filled += take;
            counter++;
        }
        return out;
    }

    const wrappingKey = (sharedSecret) =>
        hkdfSha256(sharedSecret, sodium.from_string(HKDF_SALT), sodium.from_string(HKDF_INFO), 32);

    // ---------- the account key ----------

    /** the stored account key, or null when this device has none yet */
    async function getMasterKey() {
        await ready;
        return idbGet(MASTER);
    }

    /** the stored account key, generated on first use — the primary device seeds it */
    async function ensureMasterKey() {
        await ready;
        let key = await idbGet(MASTER);
        if (!key) {
            key = sodium.randombytes_buf(32);
            await idbPut(MASTER, key);
        }
        return key;
    }

    async function setMasterKey(bytes) {
        await ready;
        await idbPut(MASTER, new Uint8Array(bytes));
    }

    async function hasMasterKey() {
        return (await getMasterKey()) !== null;
    }

    // ---------- this device's signing identity ----------

    async function ensureIdentity() {
        await ready;
        let identity = await idbGet(IDENTITY);
        if (!identity) {
            const pair = sodium.crypto_sign_keypair();
            identity = {publicKey: pair.publicKey, privateKey: pair.privateKey};
            await idbPut(IDENTITY, identity);
        }
        return identity;
    }

    // ---------- the pairing exchange ----------

    /** a single-use X25519 keypair, made fresh for every pairing attempt */
    function newPairingKey() {
        const pair = sodium.crypto_box_keypair();
        return {publicKey: pair.publicKey, privateKey: pair.privateKey};
    }

    function signingMessage(userCode, pk, nonce, wrapped) {
        return sodium.from_string(
            [SIGN_CONTEXT, compact(userCode), pk, nonce, wrapped].join('|'));
    }

    /**
     * Approving side: seal this device's account key for the newcomer.
     *
     * @param newcomerPublicKey base64 X25519 key the newcomer offered
     * @param userCode the pairing code both sides can see
     * @return the JSON bundle to hand back through the server, unreadable to it
     */
    async function sealForNewcomer(newcomerPublicKey, userCode) {
        await ready;
        const master = await ensureMasterKey();
        const identity = await ensureIdentity();

        const ephemeral = sodium.crypto_box_keypair();
        const shared = sodium.crypto_scalarmult(ephemeral.privateKey, unb64(newcomerPublicKey));
        const nonce = sodium.randombytes_buf(SECRETBOX_NONCE);
        const wrapped = sodium.crypto_secretbox_easy(master, nonce, wrappingKey(shared));

        const pk = b64(ephemeral.publicKey);
        const nonceText = b64(nonce);
        const wrappedText = b64(wrapped);
        const signature = sodium.crypto_sign_detached(
            signingMessage(userCode, pk, nonceText, wrappedText), identity.privateKey);

        return JSON.stringify({
            v: 1,
            pk: pk,
            nonce: nonceText,
            wrapped: wrappedText,
            signPk: b64(identity.publicKey),
            sig: b64(signature)
        });
    }

    /**
     * Newcomer side: open the approving device's bundle and keep the account key.
     *
     * @param bundle the JSON string (or already-parsed object) from the token claim
     * @param privateKey this pairing's X25519 private key
     * @param userCode the code shown while pairing
     * @return the account key, now stored
     */
    async function openFromApprover(bundle, privateKey, userCode) {
        await ready;
        const pkg = typeof bundle === 'string' ? JSON.parse(bundle) : bundle;
        if (!pkg || pkg.v !== 1) {
            throw new Error('unknown key package');
        }

        const ok = sodium.crypto_sign_verify_detached(
            unb64(pkg.sig),
            signingMessage(userCode, pkg.pk, pkg.nonce, pkg.wrapped),
            unb64(pkg.signPk));
        if (!ok) {
            throw new Error('the account key did not come from the device that approved it');
        }

        const shared = sodium.crypto_scalarmult(privateKey, unb64(pkg.pk));
        const master = sodium.crypto_secretbox_open_easy(
            unb64(pkg.wrapped), unb64(pkg.nonce), wrappingKey(shared));
        await setMasterKey(master);
        return master;
    }

    return {
        ready,
        b64,
        unb64,
        compact,
        hasMasterKey,
        getMasterKey,
        ensureMasterKey,
        setMasterKey,
        ensureIdentity,
        newPairingKey,
        sealForNewcomer,
        openFromApprover
    };
})();

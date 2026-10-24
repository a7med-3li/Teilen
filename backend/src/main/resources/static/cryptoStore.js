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
    const LEGACY_KEYS = 'account_legacy_keys';
    const IDENTITY = 'identity_ed25519';

    // the string the approving device signs over, so a relayed bundle cannot be re-pointed
    const SIGN_CONTEXT = 'teilen-pairing-v1';
    // domain separation for the key that wraps the account key, both sides must agree
    const HKDF_SALT = 'teilen-pairing';
    const HKDF_INFO = 'teilen-master-key';
    const HOST_INVITE_SALT = 'teilen-host-invite';
    const HOST_INVITE_INFO = 'teilen-key-bundle';
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

    // ---------- IndexedDB, one store, key/value with in-memory fallback ----------

    const memStore = new Map();
    let idbDisabled = false;

    function openDb() {
        if (idbDisabled || typeof indexedDB === 'undefined') {
            return Promise.reject(new Error('IndexedDB disabled or unavailable'));
        }
        return new Promise((resolve, reject) => {
            try {
                const request = indexedDB.open(DB_NAME, 1);
                request.onupgradeneeded = () => {
                    try {
                        request.result.createObjectStore(STORE);
                    } catch (e) {}
                };
                request.onsuccess = () => resolve(request.result);
                request.onerror = () => {
                    idbDisabled = true;
                    reject(request.error || new Error('IndexedDB open error'));
                };
            } catch (err) {
                idbDisabled = true;
                reject(err);
            }
        });
    }

    async function idbGet(key) {
        if (!idbDisabled && typeof indexedDB !== 'undefined') {
            try {
                const db = await openDb();
                return await new Promise((resolve) => {
                    try {
                        const tx = db.transaction(STORE, 'readonly');
                        const request = tx.objectStore(STORE).get(key);
                        request.onsuccess = () => resolve(request.result !== undefined ? request.result : (memStore.get(key) || null));
                        request.onerror = () => resolve(memStore.get(key) || null);
                    } catch (e) {
                        idbDisabled = true;
                        resolve(memStore.get(key) || null);
                    }
                });
            } catch (e) {
                idbDisabled = true;
            }
        }
        return memStore.get(key) || null;
    }

    async function idbPut(key, value) {
        memStore.set(key, value);
        if (!idbDisabled && typeof indexedDB !== 'undefined') {
            try {
                const db = await openDb();
                await new Promise((resolve) => {
                    try {
                        const tx = db.transaction(STORE, 'readwrite');
                        tx.objectStore(STORE).put(value, key);
                        tx.oncomplete = () => resolve();
                        tx.onerror = () => resolve();
                    } catch (e) {
                        idbDisabled = true;
                        resolve();
                    }
                });
            } catch (e) {
                idbDisabled = true;
            }
        }
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
        let key = await idbGet(MASTER);
        if (!key) {
            try {
                const lsKey = localStorage.getItem('teilen_master_key');
                if (lsKey) {
                    key = unb64(lsKey);
                    await idbPut(MASTER, key);
                }
            } catch (e) {}
        }
        return key;
    }

    /** the stored account key, generated on first use — the primary device seeds it */
    async function ensureMasterKey() {
        await ready;
        let key = await getMasterKey();
        if (!key) {
            key = sodium.randombytes_buf(32);
            await setMasterKey(key);
        }
        return key;
    }

    async function setMasterKey(bytes) {
        await ready;
        const arr = new Uint8Array(bytes);
        await idbPut(MASTER, arr);
        try {
            localStorage.setItem('teilen_master_key', b64(arr));
        } catch (e) {}
    }

    async function hasMasterKey() {
        return (await getMasterKey()) !== null;
    }

    // ---------- legacy keys for non-destructive merges ----------

    async function getLegacyKeys() {
        await ready;
        const stored = await idbGet(LEGACY_KEYS);
        if (!stored) return [];
        try {
            const parsed = typeof stored === 'string' ? JSON.parse(stored) : stored;
            return Array.isArray(parsed) ? parsed : [];
        } catch (e) {
            return [];
        }
    }

    async function addLegacyKey(keyBytesOrB64) {
        if (!keyBytesOrB64) return;
        await ready;
        const keyStr = typeof keyBytesOrB64 === 'string' ? keyBytesOrB64 : b64(keyBytesOrB64);
        const currentMaster = await getMasterKey();
        if (currentMaster && b64(currentMaster) === keyStr) return;

        const list = await getLegacyKeys();
        if (!list.includes(keyStr)) {
            list.push(keyStr);
            await idbPut(LEGACY_KEYS, JSON.stringify(list));
        }
    }

    async function addLegacyKeys(keysList) {
        if (!Array.isArray(keysList)) return;
        for (const k of keysList) {
            await addLegacyKey(k);
        }
    }

    async function getAllCandidateKeys() {
        await ready;
        const master = await getMasterKey();
        const legacies = await getLegacyKeys();
        const res = [];
        if (master) res.push(master);
        for (const l of legacies) {
            try {
                res.push(unb64(l));
            } catch (e) {}
        }
        return res;
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
    /**
     * Approving side: seal this device's account key and all session keys for the newcomer.
     *
     * @param newcomerPublicKey base64 X25519 key the newcomer offered
     * @param userCode the pairing code both sides can see
     * @return the JSON bundle to hand back through the server, unreadable to it
     */
    async function sealForNewcomer(newcomerPublicKey, userCode) {
        await ready;
        const master = await ensureMasterKey();
        const identity = await ensureIdentity();
        const legacyKeys = await getLegacyKeys();
        const sessionKeys = await getAllSessionKeys();

        const payload = JSON.stringify({
            masterKey: b64(master),
            legacyKeys: legacyKeys,
            sessionKeys: sessionKeys
        });

        const ephemeral = sodium.crypto_box_keypair();
        const shared = sodium.crypto_scalarmult(ephemeral.privateKey, unb64(newcomerPublicKey));
        const nonce = sodium.randombytes_buf(SECRETBOX_NONCE);
        const wrapped = sodium.crypto_secretbox_easy(sodium.from_string(payload), nonce, wrappingKey(shared));

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
     * Newcomer side: open the approving device's bundle and keep the account key and session keys.
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
        const decryptedBytes = sodium.crypto_secretbox_open_easy(
            unb64(pkg.wrapped), unb64(pkg.nonce), wrappingKey(shared));
        if (!decryptedBytes) {
            throw new Error('failed to decrypt key package');
        }

        const currentMaster = await getMasterKey();

        try {
            const payloadStr = sodium.to_string(decryptedBytes);
            const payload = JSON.parse(payloadStr);
            if (payload && payload.masterKey) {
                const master = unb64(payload.masterKey);
                if (currentMaster && b64(currentMaster) !== b64(master)) {
                    await addLegacyKey(currentMaster);
                }
                await setMasterKey(master);
                if (Array.isArray(payload.legacyKeys)) {
                    await addLegacyKeys(payload.legacyKeys);
                }
                if (payload.sessionKeys) {
                    await setSessionKeysMap(payload.sessionKeys);
                }
                return master;
            }
        } catch (e) {
            // legacy raw 32 bytes format fallback
        }

        if (currentMaster && b64(currentMaster) !== b64(decryptedBytes)) {
            await addLegacyKey(currentMaster);
        }
        await setMasterKey(decryptedBytes);
        return decryptedBytes;
    }

    /**
     * Host Invite: seal this device's account key and session keys under the user code secret.
     */
    async function sealHostInvite(userCode) {
        await ready;
        const master = await ensureMasterKey();
        const legacyKeys = await getLegacyKeys();
        const sessionKeys = await getAllSessionKeys();

        const payload = JSON.stringify({
            masterKey: b64(master),
            legacyKeys: legacyKeys,
            sessionKeys: sessionKeys
        });

        const key = hkdfSha256(
            sodium.from_string(compact(userCode)),
            sodium.from_string(HOST_INVITE_SALT),
            sodium.from_string(HOST_INVITE_INFO),
            32
        );
        const nonce = sodium.randombytes_buf(SECRETBOX_NONCE);
        const wrapped = sodium.crypto_secretbox_easy(sodium.from_string(payload), nonce, key);

        return JSON.stringify({
            v: 1,
            type: 'host_invite',
            nonce: b64(nonce),
            wrapped: b64(wrapped)
        });
    }

    /**
     * Scanner side: open a Host Invite bundle using the user code.
     */
    async function openHostInvite(bundle, userCode, isPrimaryHost = true) {
        await ready;
        const pkg = typeof bundle === 'string' ? JSON.parse(bundle) : bundle;
        if (!pkg || pkg.v !== 1) {
            throw new Error('unknown key package');
        }

        const key = hkdfSha256(
            sodium.from_string(compact(userCode)),
            sodium.from_string(HOST_INVITE_SALT),
            sodium.from_string(HOST_INVITE_INFO),
            32
        );
        const decryptedBytes = sodium.crypto_secretbox_open_easy(
            unb64(pkg.wrapped), unb64(pkg.nonce), key
        );
        if (!decryptedBytes) {
            throw new Error('Failed to decrypt host invite bundle');
        }

        const payload = JSON.parse(sodium.to_string(decryptedBytes));
        if (!payload || !payload.masterKey) {
            throw new Error('Missing masterKey in host invite bundle');
        }

        const incomingMaster = unb64(payload.masterKey);
        const currentMaster = await getMasterKey();

        if (isPrimaryHost) {
            if (currentMaster && b64(currentMaster) !== b64(incomingMaster)) {
                await addLegacyKey(currentMaster);
            }
            await setMasterKey(incomingMaster);
        } else {
            if (currentMaster && b64(currentMaster) !== b64(incomingMaster)) {
                await addLegacyKey(incomingMaster);
            }
        }

        if (Array.isArray(payload.legacyKeys)) {
            await addLegacyKeys(payload.legacyKeys);
        }

        if (payload.sessionKeys) {
            await setSessionKeysMap(payload.sessionKeys);
        }
        return await getMasterKey();
    }

    // ---------- per-session encryption keys ----------

    async function getAllSessionKeys() {
        await ready;
        const result = {};
        if (!idbDisabled && typeof indexedDB !== 'undefined') {
            try {
                const db = await openDb();
                await new Promise((resolve) => {
                    const tx = db.transaction(STORE, 'readonly');
                    const store = tx.objectStore(STORE);
                    const req = store.openCursor();
                    req.onsuccess = (e) => {
                        const cursor = e.target.result;
                        if (cursor) {
                            if (typeof cursor.key === 'string' && cursor.key.startsWith('session_key_')) {
                                const sessionId = cursor.key.replace('session_key_', '');
                                result[sessionId] = b64(cursor.value);
                            }
                            cursor.continue();
                        } else {
                            resolve();
                        }
                    };
                    req.onerror = () => resolve();
                });
            } catch (e) {}
        }
        for (const [k, v] of memStore.entries()) {
            if (typeof k === 'string' && k.startsWith('session_key_')) {
                const sessionId = k.replace('session_key_', '');
                result[sessionId] = b64(v);
            }
        }
        return result;
    }

    async function setSessionKeysMap(keysMap) {
        if (!keysMap) return;
        for (const [sessionId, b64Key] of Object.entries(keysMap)) {
            if (sessionId && b64Key) {
                try {
                    await setSessionKey(sessionId, unb64(b64Key));
                } catch (e) {}
            }
        }
    }

    async function getSessionKey(sessionId, isPersonal = false) {
        await ready;
        if (!sessionId || isPersonal) {
            return getMasterKey();
        }
        let key = await idbGet('session_key_' + sessionId);
        if (!key && isPersonal) {
            return getMasterKey();
        }
        return key;
    }

    async function setSessionKey(sessionId, bytes) {
        await ready;
        if (!sessionId) return;
        await idbPut('session_key_' + sessionId, new Uint8Array(bytes));
    }

    async function generateSessionKey() {
        await ready;
        return sodium.randombytes_buf(32);
    }

    // ---------- DPoP device proof-of-possession signing ----------

    async function signRequest(method, path) {
        await ready;
        try {
            const identity = await ensureIdentity();
            if (!identity || !identity.privateKey) return null;
            const timestamp = Date.now().toString();
            const payload = (method || 'GET').toUpperCase() + '|' + (path || '/') + '|' + timestamp;
            const sig = sodium.crypto_sign_detached(sodium.from_string(payload), identity.privateKey);
            return {
                signature: b64(sig),
                timestamp: timestamp,
                publicKey: b64(identity.publicKey)
            };
        } catch (e) {
            console.warn('signRequest error, falling back:', e);
            return null;
        }
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
        getLegacyKeys,
        addLegacyKey,
        addLegacyKeys,
        getAllCandidateKeys,
        getSessionKey,
        setSessionKey,
        generateSessionKey,
        ensureIdentity,
        signRequest,
        newPairingKey,
        sealForNewcomer,
        openFromApprover,
        sealHostInvite,
        openHostInvite,
        getAllSessionKeys,
        setSessionKeysMap
    };
})();

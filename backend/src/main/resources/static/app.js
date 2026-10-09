/* Teilen feed: pair this browser with the phone app, then load once and stay live over a websocket. */
(() => {
    'use strict';

    const API = '/api/items';
    const AUTH = '/api/auth';

    // ---------- the token ----------

    /** localStorage throws in a private window; the feed must not care */
    function read(key) {
        try {
            return localStorage.getItem(key);
        } catch (e) {
            return null;
        }
    }

    function write(key, value) {
        try {
            localStorage.setItem(key, value);
        } catch (e) {
            /* the session lasts until this tab closes, then */
        }
    }

    function forget(key) {
        try {
            localStorage.removeItem(key);
        } catch (e) {
            /* nothing to forget */
        }
    }

    const token = {
        get() {
            return read('teilen.token');
        },
        set(value) {
            if (value) {
                write('teilen.token', value);
            } else {
                forget('teilen.token');
            }
        }
    };

    // ---------- elements ----------

    const el = (id) => document.getElementById(id);
    const feed = el('feed');
    const empty = el('empty');
    const statusEl = el('status');
    const countEl = el('count');
    const clearBtn = el('clear');
    const compose = el('compose');
    const composeInput = el('compose-input');

    const pairSection = el('pair');
    const qr = el('qr');
    const pairCode = el('pair-code');
    const pairState = el('pair-state');
    const pairRetry = el('pair-retry');

    const deviceSection = el('devices');
    const accountBtn = el('account');
    const accountPhone = el('account-phone');
    const deviceList = el('device-list');
    const approveForm = el('approve-form');
    const approveCode = el('approve-code');
    const deviceStatus = el('device-status');
    const signOutBtn = el('sign-out');

    const app = el('app');
    const foot = el('foot');

    /** id -> {item, element} */
    const cards = new Map();
    let socket = null;
    let retryDelay = 1000;
    let pollTimer = null;
    let pairTimer = null;
    // the current pairing attempt's throwaway key and the code shown, both needed to receive the
    // account key the approving device seals for this browser
    let pairKey = null;
    let pairUserCode = null;

    // ---------- transport ----------

    /**
     * Every call goes through here: the token is the credential, so no request may skip it.
     * A refusal means the token is spent, so it is thrown away and pairing starts over.
     */
    async function api(path, options) {
        const settings = Object.assign({}, options);
        const headers = Object.assign({}, settings.headers);
        const held = token.get();
        if (held) {
            headers['Authorization'] = 'Bearer ' + held;
        }
        settings.headers = headers;

        const response = await fetch(path, settings);
        if (response.status === 401) {
            token.set(null);
            showPairing();
        }
        return response;
    }

    /** the server's own wording for a failure, which is always more useful than a status code */
    async function why(response) {
        const body = await response.json().catch(() => ({}));
        return body.message || 'HTTP ' + response.status;
    }

    async function postJson(path, body) {
        return api(path, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(body)
        });
    }

    function setStatus(text, cls) {
        statusEl.textContent = text;
        statusEl.className = 'status ' + cls;
    }

    // ---------- pairing ----------

    function showPairing() {
        pairSection.hidden = false;
        app.hidden = true;
        foot.hidden = true;
        deviceSection.hidden = true;
        accountBtn.hidden = true;
        stopSocket();
        stopPolling();
        startPairing();
    }

    function showFeed() {
        pairSection.hidden = true;
        app.hidden = false;
        foot.hidden = false;
        accountBtn.hidden = false;
        setStatus('connecting…', 'offline');
        load().then(connect);
        loadAccount();
    }

    /** step one: ask for a code to show */
    async function startPairing() {
        clearTimeout(pairTimer);
        pairRetry.hidden = true;
        pairCode.textContent = '····-····';
        pairState.textContent = 'asking for a code…';
        qr.removeAttribute('src');

        // offer a throwaway key so the approving device can hand back the account key sealed to it;
        // if the crypto layer is unavailable pairing still works, this browser just stays keyless
        pairKey = null;
        pairUserCode = null;
        let publicKey = null;
        try {
            await TeilenCrypto.ready;
            pairKey = TeilenCrypto.newPairingKey();
            publicKey = TeilenCrypto.b64(pairKey.publicKey);
        } catch (e) {
            pairKey = null;
        }

        let start;
        try {
            const response = await postJson(AUTH + '/device/code', {
                deviceName: describeBrowser(),
                deviceType: 'WEB',
                platform: navigator.userAgent,
                publicKey: publicKey
            });
            if (!response.ok) {
                throw new Error(await why(response));
            }
            start = await response.json();
        } catch (e) {
            pairState.textContent = 'could not ask for a code: ' + e.message;
            pairRetry.hidden = false;
            return;
        }

        qr.src = start.qrSvgUrl;
        pairCode.textContent = start.userCode;
        pairUserCode = start.userCode;
        pairState.textContent = 'waiting for approval on your phone…';
        collectToken(start.deviceCode, start.pollIntervalSeconds * 1000);
    }

    /** step three: keep asking until somebody approves, or the code runs out */
    async function collectToken(deviceCode, every) {
        try {
            const response = await postJson(AUTH + '/device/token', {deviceCode});
            if (response.status === 202) {
                pairTimer = setTimeout(() => collectToken(deviceCode, every), every);
                return;
            }
            if (!response.ok) {
                throw new Error(await why(response));
            }
            const claim = await response.json();
            token.set(claim.token);
            // the approving device may have sealed the account key for the key we offered; keep it
            // before showing the feed. A failure here must not cost the user their pairing.
            if (claim.keyPackage && pairKey && pairUserCode) {
                try {
                    await TeilenCrypto.openFromApprover(claim.keyPackage, pairKey.privateKey, pairUserCode);
                } catch (e) {
                    console.warn('could not keep the account key from this pairing:', e);
                }
            }
            pairKey = null;
            pairUserCode = null;
            pairState.textContent = 'paired as ' + claim.deviceName;
            showFeed();
        } catch (e) {
            pairState.textContent = 'pairing did not work out: ' + e.message;
            pairRetry.hidden = false;
        }
    }

    pairRetry.addEventListener('click', startPairing);

    // ---------- account ----------

    async function loadAccount() {
        try {
            const me = await (await api('/api/me')).json();
            accountBtn.textContent = me.displayName || me.phone;
            accountPhone.textContent = 'Signed in as ' + me.phone
                + (me.displayName ? ' (' + me.displayName + ')' : '')
                + '. Anything else that pairs with this account can read the same feed.';

            const devices = await (await api('/api/devices')).json();
            renderDevices(devices);
        } catch (e) {
            /* the feed matters more than the device list */
        }
    }

    function renderDevices(devices) {
        deviceList.replaceChildren(...devices.map((device) => {
            const row = document.createElement('li');
            row.className = 'device';

            const label = document.createElement('span');
            label.className = 'device-name';
            label.textContent = device.name;

            const kind = document.createElement('span');
            kind.className = 'device-kind';
            kind.textContent = device.deviceType === 'PHONE' ? 'phone' : 'web';

            const when = document.createElement('span');
            when.className = 'device-when';
            when.textContent = device.current
                ? 'this browser · paired ' + when_(device.pairedAt)
                : 'last used ' + when_(device.lastSeenAt);

            row.append(label, kind, when);

            if (!device.current) {
                const revoke = document.createElement('button');
                revoke.className = 'linkish';
                revoke.type = 'button';
                revoke.textContent = 'unpair';
                revoke.addEventListener('click', async () => {
                    revoke.disabled = true;
                    const response = await api('/api/devices/' + device.id, {method: 'DELETE'});
                    deviceStatus.hidden = false;
                    if (response.ok) {
                        deviceStatus.textContent = device.name + ' was unpaired.';
                        loadAccount();
                    } else {
                        deviceStatus.textContent = await why(response);
                        revoke.disabled = false;
                    }
                });
                row.append(revoke);
            } else {
                const badge = document.createElement('span');
                badge.className = 'device-kind current';
                badge.textContent = 'this one';
                row.append(badge);
            }
            return row;
        }));
    }

    function when_(iso) {
        const then = new Date(iso);
        const minutes = Math.round((Date.now() - then.getTime()) / 60000);
        if (minutes < 1) {
            return 'just now';
        }
        if (minutes < 60) {
            return minutes + ' min ago';
        }
        const hours = Math.round(minutes / 60);
        return hours < 24 ? hours + ' h ago' : then.toLocaleDateString();
    }

    accountBtn.addEventListener('click', () => {
        const open = deviceSection.hidden;
        deviceSection.hidden = !open;
        accountBtn.setAttribute('aria-expanded', String(open));
        if (open) {
            loadAccount();
        }
    });

    /** the other way to pair: a phone shows its code and it is typed in here */
    approveForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        const userCode = approveCode.value.trim();
        if (!userCode) {
            return;
        }
        approveCode.value = '';
        deviceStatus.hidden = false;
        deviceStatus.textContent = 'pairing…';

        // seal this browser's account key to the newcomer's key, if it offered one. Without that the
        // pair still works, the newcomer just has no way to read anything sealed later.
        let keyPackage = null;
        try {
            await TeilenCrypto.ready;
            const preview = await (await api(AUTH + '/device/pending?code=' + encodeURIComponent(userCode))).json();
            if (preview && preview.publicKey) {
                keyPackage = await TeilenCrypto.sealForNewcomer(preview.publicKey, userCode);
            }
        } catch (err) {
            keyPackage = null;
        }

        const response = await postJson(AUTH + '/device/approve', {userCode, keyPackage});
        if (response.ok) {
            const approved = await response.json();
            deviceStatus.textContent = approved.deviceName + ' is paired. It picks its token up on its own.';
        } else {
            deviceStatus.textContent = await why(response);
        }
    });

    signOutBtn.addEventListener('click', async () => {
        deviceStatus.hidden = false;
        deviceStatus.textContent = 'signing out…';
        // unpair this browser for real; the server refuses only if it is the last way into the
        // account, in which case forgetting the token here is all that can be done anyway
        try {
            const devices = await (await api('/api/devices')).json();
            const mine = devices.find((d) => d.current);
            if (mine) {
                const response = await api('/api/devices/' + mine.id, {method: 'DELETE'});
                if (!response.ok) {
                    deviceStatus.textContent = 'signed out of this browser, but it stays paired: '
                        + await why(response);
                }
            }
        } catch (e) {
            /* forgetting locally is the part that matters */
        }
        token.set(null);
        showPairing();
    });

    /** enough for the approval prompt to name the newcomer without a library */
    function describeBrowser() {
        const ua = navigator.userAgent;
        const browser = /Firefox\//.test(ua) ? 'Firefox'
            : /Edg\//.test(ua) ? 'Edge'
                : /OPR\//.test(ua) ? 'Opera'
                    : /Chrome\//.test(ua) ? 'Chrome'
                        : /Safari\//.test(ua) ? 'Safari'
                            : 'A browser';
        const os = /\bAndroid\b/.test(ua) ? 'Android'
            : /\biPhone|\biPad\b/.test(ua) ? 'iOS'
                : /\bWindows\b/.test(ua) ? 'Windows'
                    : /\bMac OS X\b/.test(ua) ? 'macOS'
                        : /\bLinux\b/.test(ua) ? 'Linux'
                            : 'something';
        return browser + ' on ' + os;
    }

    // ---------- rendering ----------

    const BLOB_TYPES = new Set(['IMAGE', 'PDF', 'FILE']);

    function formatSize(bytes) {
        if (!bytes && bytes !== 0) {
            return '';
        }
        if (bytes < 1024) {
            return bytes + ' B';
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024).toFixed(0) + ' KB';
        }
        return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    }

    function upsert(item, isNew) {
        if (cards.has(item.id)) {
            return;
        }
        const li = document.createElement('li');
        li.className = 'card' + (isNew ? ' new' : '');
        li.dataset.id = item.id;

        const head = document.createElement('div');
        head.className = 'card-head';
        const badge = document.createElement('span');
        badge.className = 'badge ' + item.type.toLowerCase();
        badge.textContent = badgeLabel(item);
        const at = document.createElement('span');
        at.textContent = new Date(item.createdAt).toLocaleTimeString();
        const ttl = document.createElement('span');
        ttl.className = 'ttl';
        head.append(badge, at, ttl);

        const body = document.createElement('div');
        body.className = 'card-body';
        if (item.type === 'LINK') {
            const a = document.createElement('a');
            a.href = item.content;
            a.target = '_blank';
            a.rel = 'noopener noreferrer';
            a.textContent = item.content;
            body.append(a);
        } else if (BLOB_TYPES.has(item.type)) {
            body.append(fileBody(item));
        } else {
            body.textContent = item.content;
        }

        li.append(head, body);
        feed.prepend(li);
        cards.set(item.id, {item, element: li, ttl});
        setTimeout(() => li.classList.remove('new'), 1500);
        refresh();
    }

    function badgeLabel(item) {
        if (item.type === 'LINK') return 'link';
        if (item.type === 'IMAGE') return 'image';
        if (item.type === 'PDF') return 'pdf';
        if (item.type === 'FILE') return 'file';
        return 'text';
    }

    /**
     * Images preview inline, everything else opens in a new tab — neither can carry a bearer
     * token, which is what the signed link in every item is for.
     */
    function fileBody(item) {
        const wrap = document.createElement('div');
        wrap.className = 'file';

        const href = item.blobUrl || (API + '/' + item.id + '/blob');
        const meta = document.createElement('div');
        meta.className = 'file-meta';

        const name = document.createElement('a');
        name.href = href;
        name.target = '_blank';
        name.rel = 'noopener noreferrer';
        name.className = 'file-name';
        name.textContent = item.content;
        meta.append(name);

        const size = formatSize(item.sizeBytes);
        if (size) {
            const s = document.createElement('span');
            s.className = 'file-size';
            s.textContent = size;
            meta.append(s);
        }
        wrap.append(meta);

        if (item.type === 'IMAGE') {
            const preview = document.createElement('img');
            preview.className = 'file-preview';
            preview.src = href;
            preview.alt = item.content;
            preview.loading = 'lazy';
            preview.addEventListener('error', () => preview.remove());
            wrap.append(preview);
        } else {
            const open = document.createElement('a');
            open.className = 'file-open';
            open.href = href;
            open.target = '_blank';
            open.rel = 'noopener noreferrer';
            open.textContent = item.type === 'PDF' ? 'Open PDF' : 'Open';
            wrap.append(open);
        }
        return wrap;
    }

    function remove(id) {
        const entry = cards.get(id);
        if (!entry) {
            return;
        }
        entry.element.remove();
        cards.delete(id);
        refresh();
    }

    function refresh() {
        countEl.textContent = cards.size === 1 ? '1 item' : cards.size + ' items';
        empty.hidden = cards.size > 0;
        clearBtn.hidden = cards.size === 0;
    }

    function remaining(item) {
        return Math.max(0, Math.round((new Date(item.expiresAt).getTime() - Date.now()) / 1000));
    }

    function formatLeft(seconds) {
        if (seconds <= 0) {
            return 'expired';
        }
        const h = Math.floor(seconds / 3600);
        const m = Math.floor((seconds % 3600) / 60);
        const s = seconds % 60;
        if (h > 0) {
            return h + 'h ' + String(m).padStart(2, '0') + 'm left';
        }
        return m > 0 ? m + 'm ' + String(s).padStart(2, '0') + 's left' : s + 's left';
    }

    // one timer for every card: tick the countdown and drop anything that ran out
    setInterval(() => {
        for (const [id, entry] of cards) {
            const left = remaining(entry.item);
            entry.ttl.textContent = formatLeft(left);
            if (left <= 0) {
                remove(id);
            }
        }
    }, 1000);

    async function load() {
        try {
            const response = await api(API);
            if (!response.ok) {
                throw new Error(await why(response));
            }
            const items = await response.json();
            // newest first
            items.reverse().forEach((item) => upsert(item, false));
        } catch (e) {
            console.warn('load failed', e);
        }
    }

    function connect() {
        stopSocket();
        const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
        // a browser cannot set a header on a websocket, so the handshake passes the token along
        const live = new WebSocket(scheme + '://' + location.host + '/ws?token='
            + encodeURIComponent(token.get() || ''));
        socket = live;

        live.addEventListener('open', () => {
            retryDelay = 1000;
            setStatus('live', 'live');
            stopPolling();
        });

        live.addEventListener('message', (message) => {
            let event;
            try {
                event = JSON.parse(message.data);
            } catch (e) {
                return;
            }
            if (event.event === 'created' && event.item) {
                upsert(event.item, true);
            } else if (event.event === 'deleted' && event.id) {
                remove(event.id);
            }
        });

        live.addEventListener('close', () => {
            // a socket we have already replaced or thrown away has nothing to reconnect
            if (socket !== live) {
                return;
            }
            socket = null;
            setStatus('reconnecting…', 'offline');
            startPolling();
            setTimeout(connect, retryDelay);
            retryDelay = Math.min(retryDelay * 2, 15000);
        });

        live.addEventListener('error', () => live.close());
    }

    function stopSocket() {
        const old = socket;
        socket = null;
        if (old) {
            old.close();
        }
    }

    // if the socket is down, the feed still updates by polling
    function startPolling() {
        if (pollTimer) {
            return;
        }
        pollTimer = setInterval(async () => {
            const ids = new Set();
            try {
                const response = await api(API);
                const items = await response.json();
                items.forEach((item) => {
                    ids.add(item.id);
                    upsert(item, false);
                });
            } catch (e) {
                /* keep trying */
            }
            for (const id of [...cards.keys()]) {
                if (!ids.has(id)) {
                    remove(id);
                }
            }
        }, 3000);
    }

    function stopPolling() {
        clearInterval(pollTimer);
        pollTimer = null;
    }

    // ---------- actions ----------

    compose.addEventListener('submit', async (e) => {
        e.preventDefault();
        const content = composeInput.value.trim();
        if (!content) {
            return;
        }
        composeInput.value = '';
        const response = await postJson(API, {content});
        if (!response.ok) {
            const message = await why(response);
            alert('could not send: ' + message);
            composeInput.value = content;
        }
    });

    // ---------- files ----------

    const upload = el('upload');
    const uploadInput = el('upload-input');
    const uploadLabel = el('upload-label');
    const uploadTtl = el('upload-ttl');
    const uploadSend = el('upload-send');
    const uploadStatus = el('upload-status');
    const MAX_BYTES = 30 * 1024 * 1024;

    function showUploadStatus(text, isError) {
        uploadStatus.textContent = text;
        uploadStatus.hidden = !text;
        uploadStatus.classList.toggle('error', !!isError);
    }

    uploadInput.addEventListener('change', () => {
        const files = [...uploadInput.files];
        uploadSend.hidden = files.length === 0;
        if (files.length === 0) {
            uploadLabel.textContent = 'Attach files…';
            return;
        }
        const names = files.map((f) => f.name).join(', ');
        uploadLabel.textContent = files.length === 1 ? names : files.length + ' files selected';
        // check up front so an oversized file never leaves the browser
        const tooBig = files.filter((f) => f.size > MAX_BYTES).map((f) => f.name);
        if (tooBig.length) {
            showUploadStatus('over the 30 MB limit: ' + tooBig.join(', '), true);
        } else {
            showUploadStatus('');
        }
    });

    upload.addEventListener('submit', async (e) => {
        e.preventDefault();
        const files = [...uploadInput.files];
        if (files.length === 0) {
            return;
        }
        uploadSend.disabled = true;
        const ttl = uploadTtl.value;
        let sent = 0;
        for (const file of files) {
            showUploadStatus('sending ' + file.name + ' (' + sent + '/' + files.length + ')…', false);
            const body = new FormData();
            body.append('file', file);
            body.append('ttlSeconds', ttl);
            const response = await api(API, {method: 'POST', body});
            if (!response.ok) {
                showUploadStatus(file.name + ': ' + (await why(response)), true);
                uploadSend.disabled = false;
                return;
            }
            sent++;
        }
        uploadInput.value = '';
        uploadLabel.textContent = 'Attach files…';
        uploadSend.hidden = true;
        uploadSend.disabled = false;
        showUploadStatus(sent === 1 ? '1 file sent' : sent + ' files sent', false);
        setTimeout(() => showUploadStatus(''), 4000);
    });

    clearBtn.addEventListener('click', async () => {
        await Promise.all([...cards.keys()].map((id) =>
            api(API + '/' + id, {method: 'DELETE'}).catch(() => {})));
    });

    // ---------- start ----------

    refresh();
    if (token.get()) {
        showFeed();
    } else {
        showPairing();
    }
})();
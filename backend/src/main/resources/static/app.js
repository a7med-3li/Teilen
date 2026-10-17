/* ==========================================================================
   Teilen Web Client: Vanilla ES6+ SPA
   Zero-knowledge, dual-path (Direct LAN WebRTC + Ephemeral Cloud Relay)
   ========================================================================== */
(() => {
    'use strict';

    const API = '/api/items';
    const AUTH = '/api/auth';
    const SVG_NS = 'http://www.w3.org/2000/svg';
    const MAX_BYTES = 30 * 1024 * 1024; // 30 MB ceiling

    // ---------- the token & storage ----------

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
            /* the session lasts until this tab closes */
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

    const authTabLogin = el('auth-tab-login');
    const authTabPair = el('auth-tab-pair');
    const loginContainer = el('login-container');
    const pairContainer = el('pair-container');
    const loginForm = el('login-form');
    const loginPhone = el('login-phone');
    const loginPassword = el('login-password');
    const loginStatus = el('login-status');

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
    const dropVeil = el('drop-veil');

    const upload = el('upload');
    const uploadInput = el('upload-input');
    const uploadLabel = el('upload-label');
    const uploadTtl = el('upload-ttl');
    const uploadSend = el('upload-send');
    const uploadStatus = el('upload-status');

    /** id -> {item, element, ttl, ring} */
    const cards = new Map();
    let socket = null;
    let retryDelay = 1000;
    let pollTimer = null;
    let pairTimer = null;

    // the current pairing attempt's throwaway key and the code shown
    let pairKey = null;
    let pairUserCode = null;

    // ---------- toast notifications ----------

    function toast(message, type = 'info', duration = 3500) {
        const container = el('toast-container');
        if (!container) return;
        const t = document.createElement('div');
        t.className = 'toast ' + type;
        t.textContent = message;
        container.appendChild(t);
        requestAnimationFrame(() => t.classList.add('show'));
        setTimeout(() => {
            t.classList.remove('show');
            t.addEventListener('transitionend', () => t.remove(), {once: true});
            setTimeout(() => t.remove(), 400);
        }, duration);
    }

    // ---------- transport ----------

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

    // ---------- push notifications ----------

    async function enablePush() {
        if (!('serviceWorker' in navigator) || !('PushManager' in window) || !token.get()) {
            return;
        }
        try {
            const registration = await navigator.serviceWorker.register('/sw.js');
            const response = await api('/api/push/public-key');
            if (!response.ok) return;
            const {publicKey} = await response.json();
            if (!publicKey) return;
            const existing = await registration.pushManager.getSubscription();
            const subscription = existing || await registration.pushManager.subscribe({
                userVisibleOnly: true,
                applicationServerKey: urlBase64ToUint8Array(publicKey)
            });
            await api('/api/push/subscribe', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(subscription.toJSON())
            });
        } catch (e) {
            console.warn('push is unavailable:', e);
        }
    }

    function urlBase64ToUint8Array(base64String) {
        const padding = '='.repeat((4 - base64String.length % 4) % 4);
        const base64 = (base64String + padding).replace(/-/g, '+').replace(/_/g, '/');
        const raw = atob(base64);
        const bytes = new Uint8Array(raw.length);
        for (let i = 0; i < raw.length; i++) {
            bytes[i] = raw.charCodeAt(i);
        }
        return bytes;
    }

    // ---------- direct LAN fast-path ----------

    function startLan() {
        if (window.TeilenLan && token.get()) {
            TeilenLan.start(token.get(), (item, isNew) => {
                item.isLan = true;
                upsert(item, isNew);
                toast('⚡ Received file via Direct LAN', 'success');
            });
        }
    }

    function stopLan() {
        if (window.TeilenLan) {
            TeilenLan.stop();
        }
    }

    function setStatus(text, cls) {
        const textEl = el('status-text');
        if (textEl) {
            textEl.textContent = text;
        } else {
            statusEl.textContent = text;
        }
        statusEl.className = 'status ' + cls;
    }

    // ---------- pairing & login (zero page reloads) ----------

    authTabLogin.addEventListener('click', () => {
        authTabLogin.classList.add('active');
        authTabPair.classList.remove('active');
        loginContainer.hidden = false;
        pairContainer.hidden = true;
    });

    authTabPair.addEventListener('click', () => {
        authTabPair.classList.add('active');
        authTabLogin.classList.remove('active');
        loginContainer.hidden = true;
        pairContainer.hidden = false;
        startPairing();
    });

    loginForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        const phone = loginPhone.value.trim();
        const password = loginPassword.value;
        if (!phone || !password) return;
        loginStatus.hidden = false;
        loginStatus.textContent = 'signing in…';
        try {
            const res = await postJson(AUTH + '/login', {
                phone,
                password,
                deviceName: describeBrowser(),
                deviceType: 'WEB'
            });
            if (res.ok) {
                const session = await res.json();
                token.set(session.token);
                loginStatus.hidden = true;
                loginPhone.value = '';
                loginPassword.value = '';
                toast('✓ Signed in successfully', 'success');
                showFeed();
            } else {
                loginStatus.textContent = await why(res);
            }
        } catch (err) {
            loginStatus.textContent = err.message;
        }
    });

    function showPairing() {
        pairSection.hidden = false;
        app.hidden = true;
        foot.hidden = true;
        deviceSection.hidden = true;
        accountBtn.hidden = true;
        stopSocket();
        stopPolling();
        stopLan();
        authTabLogin.classList.add('active');
        authTabPair.classList.remove('active');
        loginContainer.hidden = false;
        pairContainer.hidden = true;
    }

    function showFeed() {
        pairSection.hidden = true;
        app.hidden = false;
        foot.hidden = false;
        accountBtn.hidden = false;
        deviceSection.hidden = true;
        setStatus('connecting…', 'offline');
        load().then(connect);
        loadAccount();
        startLan();
    }

    /** step one: ask for a code to show */
    async function startPairing() {
        clearTimeout(pairTimer);
        pairRetry.hidden = true;
        pairCode.textContent = '····-····';
        pairState.textContent = 'asking for a code…';
        qr.removeAttribute('src');

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
            toast('✓ Paired as ' + claim.deviceName, 'success');
            showFeed();
            enablePush();
        } catch (e) {
            pairState.textContent = 'pairing did not work out: ' + e.message;
            pairRetry.hidden = false;
        }
    }

    pairRetry.addEventListener('click', startPairing);

    // ---------- account devices ----------

    async function loadAccount() {
        try {
            const me = await (await api('/api/me')).json();
            accountBtn.textContent = me.displayName || me.phone;
            accountPhone.textContent = 'Signed in as ' + me.phone
                + (me.displayName ? ' (' + me.displayName + ')' : '')
                + '. Devices on this account share the same ephemeral feed.';

            const devices = await (await api('/api/devices')).json();
            renderDevices(devices);
        } catch (e) {
            /* feed is primary */
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
                        toast(device.name + ' unpaired', 'info');
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
        if (minutes < 1) return 'just now';
        if (minutes < 60) return minutes + ' min ago';
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

    approveForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        const userCode = approveCode.value.trim();
        if (!userCode) return;
        approveCode.value = '';
        deviceStatus.hidden = false;
        deviceStatus.textContent = 'pairing…';

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
            deviceStatus.textContent = approved.deviceName + ' is paired successfully.';
            toast('✓ ' + approved.deviceName + ' paired', 'success');
            loadAccount();
        } else {
            deviceStatus.textContent = await why(response);
        }
    });

    signOutBtn.addEventListener('click', async () => {
        deviceStatus.hidden = false;
        deviceStatus.textContent = 'signing out…';
        try {
            const devices = await (await api('/api/devices')).json();
            const mine = devices.find((d) => d.current);
            if (mine) {
                await api('/api/devices/' + mine.id, {method: 'DELETE'});
            }
        } catch (e) {
            /* locally clearing token is essential */
        }
        token.set(null);
        toast('Signed out', 'info');
        showPairing();
    });

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
                            : 'Desktop';
        return browser + ' on ' + os;
    }

    // ---------- rendering & cards ----------

    const BLOB_TYPES = new Set(['IMAGE', 'PDF', 'FILE']);

    function formatSize(bytes) {
        if (!bytes && bytes !== 0) return '';
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(0) + ' KB';
        return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    }

    function upsert(item, isNew) {
        if (cards.has(item.id)) {
            return;
        }
        const li = document.createElement('li');
        li.className = 'card' + (isNew ? ' new' : '');
        li.dataset.id = item.id;

        // Card Header
        const head = document.createElement('div');
        head.className = 'card-head';

        const headLeft = document.createElement('div');
        headLeft.className = 'card-head-left';

        const badge = document.createElement('span');
        badge.className = 'badge ' + item.type.toLowerCase();
        badge.textContent = badgeLabel(item);

        const isLan = !!(item.isLan || String(item.id).startsWith('lan-'));
        const routePill = document.createElement('span');
        routePill.className = 'route-pill ' + (isLan ? 'lan' : 'relay');
        routePill.textContent = isLan ? '⚡ Direct LAN' : '☁️ Encrypted Relay';

        const at = document.createElement('span');
        at.className = 'card-time';
        at.textContent = new Date(item.createdAt).toLocaleTimeString([], {hour: '2-digit', minute: '2-digit'});

        headLeft.append(badge, routePill, at);

        const headRight = document.createElement('div');
        headRight.className = 'card-head-right';

        const clock = document.createElement('span');
        clock.className = 'ttl';
        const ring = document.createElementNS(SVG_NS, 'svg');
        ring.setAttribute('class', 'ring');
        ring.setAttribute('viewBox', '0 0 36 36');
        ring.setAttribute('width', '26');
        ring.setAttribute('height', '26');
        const track = document.createElementNS(SVG_NS, 'circle');
        track.setAttribute('class', 'ring-track');
        track.setAttribute('cx', '18');
        track.setAttribute('cy', '18');
        track.setAttribute('r', '15.5');
        const fill = document.createElementNS(SVG_NS, 'circle');
        fill.setAttribute('class', 'ring-fill');
        fill.setAttribute('cx', '18');
        fill.setAttribute('cy', '18');
        fill.setAttribute('r', '15.5');
        ring.append(track, fill);
        const ttlText = document.createElement('span');
        ttlText.className = 'ttl-text';
        clock.append(ring, ttlText);

        const extendBtn = document.createElement('button');
        extendBtn.type = 'button';
        extendBtn.className = 'btn-extend';
        extendBtn.title = 'Extend lifespan (+1 hour)';
        extendBtn.textContent = '+1h';
        extendBtn.hidden = isLan;
        extendBtn.addEventListener('click', () => extendItem(item.id));

        // Delete button for EACH card alone
        const deleteBtn = document.createElement('button');
        deleteBtn.type = 'button';
        deleteBtn.className = 'btn-card-delete';
        deleteBtn.title = 'Delete this item';
        deleteBtn.textContent = '✕';
        deleteBtn.addEventListener('click', async (e) => {
            e.stopPropagation();
            deleteBtn.disabled = true;
            if (isLan) {
                remove(item.id);
                toast('Item deleted', 'info');
                return;
            }
            try {
                const response = await api(API + '/' + item.id, {method: 'DELETE'});
                if (response.ok || response.status === 204) {
                    remove(item.id);
                    toast('Item deleted', 'info');
                } else {
                    deleteBtn.disabled = false;
                    toast('Could not delete: ' + (await why(response)), 'error');
                }
            } catch (err) {
                deleteBtn.disabled = false;
                toast('Network error deleting item', 'error');
            }
        });

        headRight.append(clock, extendBtn, deleteBtn);
        head.append(headLeft, headRight);

        // Card Body
        const body = document.createElement('div');
        body.className = 'card-body';

        if (item.isDecrypting) {
            // Skeleton shimmer state while decrypt.worker.js reassembles payloads
            const shimmer = document.createElement('div');
            shimmer.className = 'skeleton-shimmer';
            const b1 = document.createElement('div');
            b1.className = 'skeleton-bone short';
            const b2 = document.createElement('div');
            b2.className = 'skeleton-bone box';
            shimmer.append(b1, b2);
            body.append(shimmer);
            li.classList.add('skeleton');
        } else if (item.type === 'LINK') {
            const a = document.createElement('a');
            a.href = item.content;
            a.target = '_blank';
            a.rel = 'noopener noreferrer';
            a.textContent = item.content;
            body.append(a);

            const actions = document.createElement('div');
            actions.className = 'card-actions-bar';
            const copyBtn = document.createElement('button');
            copyBtn.type = 'button';
            copyBtn.className = 'btn-card-copy';
            copyBtn.textContent = 'Copy Link';
            copyBtn.addEventListener('click', async () => {
                await navigator.clipboard.writeText(item.content);
                copyBtn.textContent = '✓ Copied';
                copyBtn.classList.add('copied');
                toast('✓ Copied URL to clipboard', 'success');
                setTimeout(() => {
                    copyBtn.textContent = 'Copy Link';
                    copyBtn.classList.remove('copied');
                }, 1500);
            });
            actions.append(copyBtn);
            body.append(actions);
        } else if (BLOB_TYPES.has(item.type)) {
            body.append(fileBody(item));
        } else {
            const pre = document.createElement('div');
            pre.textContent = item.content;
            body.append(pre);

            const actions = document.createElement('div');
            actions.className = 'card-actions-bar';
            const copyBtn = document.createElement('button');
            copyBtn.type = 'button';
            copyBtn.className = 'btn-card-copy';
            copyBtn.textContent = 'Copy';
            copyBtn.addEventListener('click', async () => {
                await navigator.clipboard.writeText(item.content);
                copyBtn.textContent = '✓ Copied';
                copyBtn.classList.add('copied');
                toast('✓ Copied to clipboard', 'success');
                setTimeout(() => {
                    copyBtn.textContent = 'Copy';
                    copyBtn.classList.remove('copied');
                }, 1500);
            });
            actions.append(copyBtn);
            body.append(actions);
        }

        li.append(head, body);
        feed.prepend(li);
        cards.set(item.id, {item, element: li, ttl: ttlText, ring: fill});
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
            open.textContent = item.type === 'PDF' ? '📄 Open PDF' : '📎 Open / Download';
            wrap.append(open);
        }
        return wrap;
    }

    /** Smooth collapse & fade-out animation when item expires or is removed */
    function remove(id) {
        const entry = cards.get(id);
        if (!entry) return;
        cards.delete(id);
        const element = entry.element;
        element.classList.add('leaving');

        const onEnd = () => {
            element.remove();
            refresh();
        };
        element.addEventListener('transitionend', onEnd, {once: true});
        setTimeout(() => {
            if (element.parentNode) {
                element.remove();
                refresh();
            }
        }, 300);
    }

    function refresh() {
        const count = cards.size;
        countEl.textContent = count === 1 ? '1 active item' : count + ' active items';
        empty.hidden = count > 0;
        clearBtn.hidden = count === 0;
        if (count === 0 && typeof resetClearBtn === 'function') {
            resetClearBtn();
        }
    }

    function remaining(item) {
        return Math.max(0, Math.round((new Date(item.expiresAt).getTime() - Date.now()) / 1000));
    }

    function formatLeft(seconds) {
        if (seconds <= 0) return 'expired';
        const h = Math.floor(seconds / 3600);
        const m = Math.floor((seconds % 3600) / 60);
        const s = seconds % 60;
        if (h > 0) {
            return h + 'h ' + String(m).padStart(2, '0') + 'm left';
        }
        return m > 0 ? m + 'm ' + String(s).padStart(2, '0') + 's left' : s + 's left';
    }

    // Tick the countdown ring and drop anything that ran out
    setInterval(() => {
        for (const [id, entry] of cards) {
            const left = remaining(entry.item);
            entry.ttl.textContent = formatLeft(left);
            if (entry.ring) {
                const total = totalSeconds(entry.item);
                const fraction = total > 0 ? Math.max(0, Math.min(1, left / total)) : 0;
                const circumference = 2 * Math.PI * 15.5; // ~97.389
                entry.ring.style.strokeDasharray = circumference.toFixed(1);
                entry.ring.style.strokeDashoffset = (circumference * (1 - fraction)).toFixed(1);
                // Dynamic color shift: #38BDF8 -> #F59E0B (<20%) -> #EF4444 (<5%)
                entry.ring.classList.toggle('warn', fraction <= 0.20 && fraction > 0.05);
                entry.ring.classList.toggle('danger', fraction <= 0.05);
            }
            if (left <= 0) {
                remove(id);
            }
        }
    }, 1000);

    function totalSeconds(item) {
        return Math.max(1, Math.round((new Date(item.expiresAt).getTime()
            - new Date(item.createdAt).getTime()) / 1000));
    }

    async function extendItem(id) {
        try {
            const response = await api('/api/v1/items/' + id + '/extend', {method: 'POST'});
            if (!response.ok) {
                toast('Could not extend: ' + (await why(response)), 'error');
                return;
            }
            applyExtended(await response.json());
            toast('✓ Extended +1 hour', 'success');
        } catch (e) {
            toast('Failed to extend lifespan', 'error');
        }
    }

    function applyExtended(item) {
        const entry = cards.get(item.id);
        if (!entry) return;
        entry.item = item;
        entry.ttl.textContent = formatLeft(remaining(item));
        refresh();
    }

    async function load() {
        try {
            const response = await api(API);
            if (!response.ok) {
                throw new Error(await why(response));
            }
            const items = await response.json();
            items.reverse().forEach((item) => upsert(item, false));
        } catch (e) {
            console.warn('load failed', e);
        }
    }

    function connect() {
        stopSocket();
        const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
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
            } else if (event.event === 'extended' && event.item) {
                applyExtended(event.item);
            } else if (event.event === 'deleted' && event.id) {
                remove(event.id);
            }
        });

        live.addEventListener('close', () => {
            if (socket !== live) return;
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

    function startPolling() {
        if (pollTimer) return;
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

    // ---------- actions & sending ----------

    compose.addEventListener('submit', async (e) => {
        e.preventDefault();
        const content = composeInput.value.trim();
        if (!content) return;
        composeInput.value = '';
        toast('Encrypting & sending…', 'info');
        const response = await postJson(API, {content});
        if (response.ok) {
            toast('✓ Sent', 'success');
        } else {
            const message = await why(response);
            toast('Could not send: ' + message, 'error');
            composeInput.value = content;
        }
    });

    function showUploadStatus(text, isError) {
        uploadStatus.textContent = text;
        uploadStatus.hidden = !text;
        uploadStatus.classList.toggle('error', !!isError);
    }

    uploadInput.addEventListener('change', () => {
        const files = [...uploadInput.files];
        uploadSend.hidden = files.length === 0;
        if (files.length === 0) {
            uploadLabel.textContent = 'Attach files… (Max 30 MB)';
            return;
        }
        const names = files.map((f) => f.name).join(', ');
        uploadLabel.textContent = files.length === 1 ? names : files.length + ' files selected';
        const tooBig = files.filter((f) => f.size > MAX_BYTES).map((f) => f.name);
        if (tooBig.length) {
            showUploadStatus('Over the 30 MB limit: ' + tooBig.join(', '), true);
            toast('✕ ' + tooBig.join(', ') + ' exceeds 30 MB limit', 'error');
        } else {
            showUploadStatus('');
        }
    });

    upload.addEventListener('submit', async (e) => {
        e.preventDefault();
        const files = [...uploadInput.files];
        if (files.length === 0) return;
        uploadSend.disabled = true;
        await sendFiles(files);
        uploadInput.value = '';
        uploadLabel.textContent = 'Attach files… (Max 30 MB)';
        uploadSend.hidden = true;
        uploadSend.disabled = false;
    });

    async function sendFiles(files) {
        const usable = files.filter((f) => f.size <= MAX_BYTES);
        const tooBig = files.filter((f) => f.size > MAX_BYTES).map((f) => f.name);
        if (tooBig.length) {
            showUploadStatus('Over the 30 MB limit: ' + tooBig.join(', '), true);
            toast('✕ Over 30 MB limit: ' + tooBig.join(', '), 'error');
        }
        if (usable.length === 0) return;

        const ttl = uploadTtl.value;
        let sent = 0;
        for (const file of usable) {
            showUploadStatus('Sending ' + file.name + ' (' + (sent + 1) + '/' + usable.length + ')…', false);
            const body = new FormData();
            body.append('file', file);
            body.append('ttlSeconds', ttl);
            const response = await api(API, {method: 'POST', body});
            if (!response.ok) {
                const err = await why(response);
                showUploadStatus(file.name + ': ' + err, true);
                toast(file.name + ': ' + err, 'error');
                return;
            }
            sent++;
        }
        const msg = sent === 1 ? '1 file sent' : sent + ' files sent';
        showUploadStatus(msg, false);
        toast('✓ ' + msg, 'success');
        setTimeout(() => showUploadStatus(''), 4000);
    }

    async function sendText(text) {
        const content = (text || '').trim();
        if (!content) return;
        const response = await postJson(API, {content: content.slice(0, 20000)});
        if (response.ok) {
            toast('✓ Sent', 'success');
        } else {
            const err = await why(response);
            showUploadStatus(err, true);
            toast('Could not send: ' + err, 'error');
        }
    }

    // ---------- global paste (Ctrl+V / Cmd+V) ----------

    window.addEventListener('paste', (event) => {
        if (!token.get()) return;
        const target = event.target;
        if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)) {
            return;
        }
        const items = event.clipboardData && event.clipboardData.items;
        if (!items) return;

        const files = [];
        for (const item of items) {
            if (item.kind === 'file') {
                const file = item.getAsFile();
                if (file) files.push(file);
            } else if (item.kind === 'string' && item.type === 'text/plain') {
                item.getAsString((value) => {
                    const text = (value || '').trim();
                    if (text) {
                        toast('Pasted text — encrypting & sending…', 'info');
                        sendText(text);
                    }
                });
            }
        }
        if (files.length) {
            event.preventDefault();
            toast(`Pasted ${files.length === 1 ? files[0].name || 'file' : files.length + ' files'} — sending…`, 'info');
            sendFiles(files);
        }
    });

    // ---------- whole-window drop veil ----------

    let dragDepth = 0;
    window.addEventListener('dragenter', (event) => {
        if (!token.get()) return;
        dragDepth++;
        dropVeil.hidden = false;
    });

    window.addEventListener('dragover', (event) => {
        if (token.get()) {
            event.preventDefault();
        }
    });

    window.addEventListener('dragleave', () => {
        dragDepth = Math.max(0, dragDepth - 1);
        if (dragDepth === 0) {
            dropVeil.hidden = true;
        }
    });

    window.addEventListener('drop', (event) => {
        dragDepth = 0;
        dropVeil.hidden = true;
        if (!event.dataTransfer || !token.get()) return;
        event.preventDefault();

        const files = [...event.dataTransfer.files];
        if (files.length) {
            toast(`Dropped ${files.length === 1 ? files[0].name : files.length + ' files'} — encrypting & sending…`, 'info');
            sendFiles(files);
        } else {
            const text = event.dataTransfer.getData('text/plain');
            if (text) {
                toast('Dropped text — encrypting & sending…', 'info');
                sendText(text);
            }
        }
    });

    // ---------- delete all button with two-step inline confirmation ----------

    let clearConfirming = false;
    let clearTimer = null;

    function resetClearBtn() {
        clearConfirming = false;
        clearTimeout(clearTimer);
        clearBtn.classList.remove('confirming');
        clearBtn.innerHTML = '<span class="btn-icon">🗑️</span> Delete All';
    }

    clearBtn.addEventListener('click', async () => {
        if (cards.size === 0) return;

        // Step 1: Prompt confirmation
        if (!clearConfirming) {
            clearConfirming = true;
            clearBtn.classList.add('confirming');
            clearBtn.textContent = 'Confirm Delete All?';
            clearTimer = setTimeout(resetClearBtn, 3000);
            return;
        }

        // Step 2: Confirmed - execute batch deletion
        clearTimeout(clearTimer);
        resetClearBtn();
        clearBtn.disabled = true;

        const toDelete = [...cards.keys()];
        // Animate all cards out simultaneously
        toDelete.forEach((id) => remove(id));

        toast('All ephemeral items purged', 'info');

        await Promise.all(toDelete.map((id) => {
            if (id.startsWith('lan-')) {
                return Promise.resolve();
            }
            return api(API + '/' + id, {method: 'DELETE'}).catch(() => {});
        }));
        clearBtn.disabled = false;
    });

    // ---------- brand launch animation (1.4s) ----------

    function initLaunchScreen() {
        const veil = el('launch-veil');
        const launchStatus = el('launch-status');
        if (!veil) return;

        // Phase 3 (650ms): micro-status transition to Feed Sync
        setTimeout(() => {
            if (launchStatus) {
                launchStatus.style.opacity = '0';
                setTimeout(() => {
                    launchStatus.textContent = '⚡ Syncing Ephemeral Feed…';
                    launchStatus.style.opacity = '1';
                }, 120);
            }
        }, 650);

        // Phase 4 (1200ms - 1400ms): cross-fade to dashboard
        setTimeout(() => {
            veil.classList.add('dismissed');
            setTimeout(() => {
                veil.remove();
            }, 300);
        }, 1200);
    }

    // ---------- start ----------

    initLaunchScreen();
    refresh();
    if (token.get()) {
        showFeed();
        enablePush();
    } else {
        showPairing();
    }
})();
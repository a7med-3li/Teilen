/* ==========================================================================
   Teilen Web Client: Vanilla ES6+ SPA
   Zero-knowledge, dual-path (Direct LAN WebRTC + Ephemeral Cloud Relay)
   Multi-Session Rooms, Multi-GB LAN Streaming, Token Hardening & DPoP
   ========================================================================== */
(() => {
    'use strict';

    // Immediate guaranteed dismissal for launch veil (never traps user on black screen)
    setTimeout(() => {
        const veil = document.getElementById('launch-veil');
        if (veil) {
            veil.classList.add('dismissed');
            veil.hidden = true;
            try { veil.remove(); } catch (e) {}
        }
    }, 1400);

    const API = '/api/items';
    const AUTH = '/api/auth';
    const SESSIONS_API = '/api/sessions';
    const SVG_NS = 'http://www.w3.org/2000/svg';
    const MAX_RELAY_BYTES = 30 * 1024 * 1024; // 30 MB ceiling for Cloud Relay

    // ---------- In-Memory Token & Security Hardening (Task 6) ----------
    // Access token is held STRICTLY in memory (never written to localStorage or sessionStorage).
    // Silent refreshes occur via HttpOnly; SameSite=Strict cookies with rotation.
    let inMemoryAccessToken = null;

    const token = {
        get() {
            return inMemoryAccessToken;
        },
        set(value) {
            inMemoryAccessToken = value || null;
            // Clean up any old legacy localStorage token if it existed
            try {
                localStorage.removeItem('teilen.token');
            } catch (e) {}
        }
    };

    // ---------- Active Session State (Task 4) ----------
    let currentSessionId = null;
    let availableSessions = [];
    let activeCustomTtlItemId = null; // When editing card TTL

    // ---------- Elements ----------
    const el = (id) => document.getElementById(id);
    const feed = el('feed');
    const empty = el('empty');
    const statusEl = el('status');
    const countEl = el('count');
    const clearBtn = el('clear');
    const compose = el('compose');
    const composeInput = el('compose-input');
    const composeSubmit = el('compose-submit');

    const sessionPills = el('session-pills');
    const sessionShareBtn = el('session-share-btn');
    const sessionCreateBtn = el('session-create-btn');
    const sessionJoinBtn = el('session-join-btn');

    const modalCreateSession = el('modal-create-session');
    const formCreateSession = el('form-create-session');
    const createSessionName = el('create-session-name');

    const modalShareSession = el('modal-share-session');
    const shareSessionTitle = el('share-session-title');
    const shareSessionCode = el('share-session-code');
    const shareSessionQr = el('share-session-qr');
    const shareSessionCopy = el('share-session-copy');

    const modalJoinSession = el('modal-join-session');
    const formJoinSession = el('form-join-session');
    const joinSessionInput = el('join-session-input');
    const joinSessionPreview = el('join-session-preview');

    const modalCustomTtl = el('modal-custom-ttl');
    const formCustomTtl = el('form-custom-ttl');
    const customTtlNumber = el('custom-ttl-number');
    const customTtlUnit = el('custom-ttl-unit');
    const customTtlTitle = el('custom-ttl-title');
    const ttlCustomPill = el('ttl-custom-pill');

    const telemetryCard = el('telemetry-card');
    const telemetryName = el('telemetry-name');
    const telemetryBar = el('telemetry-bar');
    const telemetryStats = el('telemetry-stats');
    const telemetrySpeed = el('telemetry-speed');
    const telemetryCancel = el('telemetry-cancel');

    const pairSection = el('pair');
    const qr = el('qr');
    const pairCode = el('pair-code');
    const pairState = el('pair-state');
    const pairRetry = el('pair-retry');

    const btnInstantVault = el('btn-instant-vault');
    const btnToggleAuthAdvanced = el('btn-toggle-auth-advanced');
    const authAdvancedChevron = el('auth-advanced-chevron');
    const authAdvancedContainer = el('auth-advanced-container');
    const authTabLogin = el('auth-tab-login');
    const authTabRegister = el('auth-tab-register');
    const authTabPair = el('auth-tab-pair');
    const loginContainer = el('login-container');
    const registerContainer = el('register-container');
    const pairContainer = el('pair-container');
    const loginForm = el('login-form');
    const loginUsername = el('login-username');
    const loginUsernameHelper = el('login-username-helper');
    const loginPassword = el('login-password');
    const loginStatus = el('login-status');
    const registerForm = el('register-form');
    const registerUsername = el('register-username');
    const registerUsernameHelper = el('register-username-helper');
    const registerPassword = el('register-password');
    const registerDisplayName = el('register-display-name');
    const registerStatus = el('register-status');
    const pairCodeCopy = el('pair-code-copy');

    const deviceSection = el('devices');
    const accountBtn = el('account');
    const accountPhone = el('account-phone');
    const deviceList = el('device-list');
    const approveForm = el('approve-form');
    const approveCode = el('approve-code');
    const deviceStatus = el('device-status');
    const signOutBtn = el('sign-out');
    const revokeOthersBtn = el('revoke-others-btn');

    const app = el('app');
    const foot = el('foot');
    const dropVeil = el('drop-veil');

    // Header Pair & Modal Pair Elements
    const btnHeaderPair = el('btn-header-pair');
    const modalPairPhone = el('modal-pair-phone');
    const modalQr = el('modal-qr');
    const modalPairCode = el('modal-pair-code');
    const modalPairCodeCopy = el('modal-pair-code-copy');
    const modalPairStatus = el('modal-pair-status');
    const modalApproveForm = el('modal-approve-form');
    const modalApproveCode = el('modal-approve-code');
    const modalApproveStatus = el('modal-approve-status');

    // Hero Pairing Card Elements
    const heroPairingCard = el('hero-pairing-card');
    const heroQr = el('hero-qr');
    const heroPairCode = el('hero-pair-code');
    const heroCodeCopy = el('hero-code-copy');
    const heroPairStatus = el('hero-pair-status');
    const heroApproveForm = el('hero-approve-form');
    const heroApproveCode = el('hero-approve-code');
    const heroApproveStatus = el('hero-approve-status');
    const heroPairingDismiss = el('hero-pairing-dismiss');
    let heroPairingDismissed = false;

    const uploadInput = el('upload-input');
    const uploadStatus = el('upload-status');

    let selectedTtlSeconds = 3600; // Default 1 hour
    let activeLanTransfer = null;

    /** id -> {item, element, ttl, ring} */
    const cards = new Map();
    let socket = null;
    let retryDelay = 1000;
    let pollTimer = null;
    let pairTimer = null;

    let pairKey = null;
    let pairUserCode = null;

    // ---------- Toast notifications ----------
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

    // ---------- Transport with DPoP Proof-of-Possession ----------
    async function api(path, options) {
        const settings = Object.assign({}, options);
        const headers = Object.assign({}, settings.headers);
        const held = token.get();
        if (held) {
            headers['Authorization'] = 'Bearer ' + held;
        }

        // Cryptographic Proof-of-Possession (DPoP): Sign METHOD|PATH|TIMESTAMP with device key
        try {
            const method = (settings.method || 'GET').toUpperCase();
            const sigInfo = await TeilenCrypto.signRequest(method, path);
            if (sigInfo) {
                headers['X-Teilen-Signature'] = sigInfo.signature;
                headers['X-Teilen-Timestamp'] = sigInfo.timestamp;
            }
        } catch (e) {
            // Key not initialized yet during initial handshake
        }

        settings.headers = headers;
        settings.credentials = 'same-origin';

        let response = await fetch(path, settings);
        if (response.status === 401 && !path.includes('/api/auth/')) {
            const refreshed = await trySilentRefresh();
            if (refreshed) {
                headers['Authorization'] = 'Bearer ' + token.get();
                settings.headers = headers;
                response = await fetch(path, settings);
            } else {
                token.set(null);
                showPairing();
            }
        }
        return response;
    }

    async function trySilentRefresh() {
        try {
            const response = await fetch('/api/auth/refresh', {
                method: 'POST',
                credentials: 'same-origin'
            });
            if (response.ok) {
                const session = await response.json();
                token.set(session.token);
                return true;
            }
        } catch (e) {}
        return false;
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

    // ---------- Multi-Session Rooms (Spaces) (Task 4) ----------

    async function loadSessions() {
        try {
            const response = await api(SESSIONS_API);
            if (!response.ok) return;
            availableSessions = await response.json();

            if (availableSessions.length === 0) return;

            if (!currentSessionId || !availableSessions.some(s => s.id === currentSessionId)) {
                currentSessionId = availableSessions[0].id;
            }

            renderSessionPills();
            TeilenLan.setSession(currentSessionId);
        } catch (e) {
            console.warn('Failed to load sessions', e);
        }
    }

    function renderSessionPills() {
        sessionPills.replaceChildren(...availableSessions.map(session => {
            const pill = document.createElement('button');
            pill.type = 'button';
            pill.className = 'session-pill' + (session.id === currentSessionId ? ' active' : '');
            pill.dataset.id = session.id;

            const icon = session.isPersonal ? '🔒' : '👥';
            pill.innerHTML = `<span>${icon}</span> <span>${session.name}</span>` +
                (!session.isPersonal && session.memberCount > 1 ? ` <span class="session-pill-badge">${session.memberCount}</span>` : '');

            pill.addEventListener('click', () => selectSession(session.id));
            return pill;
        }));
    }

    async function selectSession(sessionId) {
        if (currentSessionId === sessionId) return;
        currentSessionId = sessionId;
        renderSessionPills();
        TeilenLan.setSession(currentSessionId);

        // Scope feed to selected session
        cards.clear();
        feed.replaceChildren();
        await load();
    }

    // Modal helpers
    function openModal(modal) {
        if (!modal) return;
        modal.hidden = false;
    }
    function closeModal(modal) {
        if (!modal) return;
        modal.hidden = true;
    }
    document.querySelectorAll('.modal-close').forEach(btn => {
        btn.addEventListener('click', (e) => {
            const veil = e.target.closest('.modal-veil');
            if (veil) closeModal(veil);
        });
    });
    document.querySelectorAll('.modal-veil').forEach(veil => {
        veil.addEventListener('click', (e) => {
            if (e.target === veil) closeModal(veil);
        });
    });

    // Create Room
    sessionCreateBtn.addEventListener('click', () => {
        createSessionName.value = '';
        openModal(modalCreateSession);
        createSessionName.focus();
    });

    formCreateSession.addEventListener('submit', async (e) => {
        e.preventDefault();
        const name = createSessionName.value.trim();
        if (!name) return;

        try {
            // Generate fresh random 256-bit symmetric session key
            const sessionKey = await TeilenCrypto.generateSessionKey();
            const response = await postJson(SESSIONS_API, {
                name,
                encryptedSessionKey: null // Master of creator wraps locally
            });

            if (!response.ok) {
                toast('Could not create room: ' + (await why(response)), 'error');
                return;
            }

            const created = await response.json();
            await TeilenCrypto.setSessionKey(created.id, sessionKey);
            closeModal(modalCreateSession);
            toast('Room created: ' + name, 'success');
            await loadSessions();
            await selectSession(created.id);
        } catch (err) {
            toast('Error creating room: ' + err.message, 'error');
        }
    });

    // Share Room (Invite Code / QR with #key fragment)
    sessionShareBtn.addEventListener('click', async () => {
        const current = availableSessions.find(s => s.id === currentSessionId);
        if (!current) return;
        if (current.isPersonal) {
            toast('Personal Vault is private to your devices. Create a shared room to invite friends or teammates.', 'info');
            return;
        }

        try {
            const response = await postJson(`${SESSIONS_API}/${current.id}/invite`, {});
            if (!response.ok) {
                toast('Could not generate invite: ' + (await why(response)), 'error');
                return;
            }
            const invite = await response.json();
            const sessionKey = await TeilenCrypto.getSessionKey(current.id);
            const keyFragment = sessionKey ? '#key=' + encodeURIComponent(TeilenCrypto.b64(sessionKey)) : '';
            const shareableUrl = `${location.origin}/#join?code=${invite.inviteCode}${keyFragment}`;

            shareSessionTitle.textContent = `Share Room: ${current.name}`;
            shareSessionCode.textContent = invite.inviteCode;

            // Render SVG QR code via backend QR endpoint
            shareSessionQr.innerHTML = `<img src="/api/auth/device/qr.svg?code=${encodeURIComponent(invite.inviteCode)}" width="200" height="200" alt="Room QR">`;

            shareSessionCopy.onclick = async () => {
                await navigator.clipboard.writeText(shareableUrl);
                toast('Copied invite link with secure key to clipboard', 'success');
            };

            openModal(modalShareSession);
        } catch (err) {
            toast('Failed to share session', 'error');
        }
    });

    // Join Room
    sessionJoinBtn.addEventListener('click', () => {
        joinSessionInput.value = '';
        joinSessionPreview.hidden = true;
        openModal(modalJoinSession);
        joinSessionInput.focus();
    });

    formJoinSession.addEventListener('submit', async (e) => {
        e.preventDefault();
        const raw = joinSessionInput.value.trim();
        if (!raw) return;

        let code = raw;
        let keyB64 = null;

        // Parse code and #key fragment if a URL was pasted
        if (raw.includes('code=')) {
            const urlObj = new URL(raw, location.origin);
            code = urlObj.searchParams.get('code') || raw;
            if (urlObj.hash.includes('key=')) {
                keyB64 = decodeURIComponent(urlObj.hash.split('key=')[1]);
            }
        } else if (raw.includes('#key=')) {
            const parts = raw.split('#key=');
            code = parts[0];
            keyB64 = decodeURIComponent(parts[1]);
        }

        code = code.replace(/[^A-Za-z0-9]/g, '').toUpperCase();

        try {
            const response = await postJson(`${SESSIONS_API}/join`, {
                inviteCode: code,
                encryptedSessionKey: null
            });

            if (!response.ok) {
                toast('Could not join room: ' + (await why(response)), 'error');
                return;
            }

            const joined = await response.json();
            if (keyB64) {
                await TeilenCrypto.setSessionKey(joined.id, TeilenCrypto.unb64(keyB64));
            }

            closeModal(modalJoinSession);
            toast('Joined room: ' + joined.name, 'success');
            await loadSessions();
            await selectSession(joined.id);
        } catch (err) {
            toast('Error joining room: ' + err.message, 'error');
        }
    });

    // Auto-join from URL hash if user opened an invite link
    async function checkUrlForInvite() {
        const hash = location.hash;
        if (hash.includes('join?code=') || hash.includes('code=')) {
            const parts = hash.split('code=')[1];
            if (parts) {
                const codeAndKey = parts.split('#key=');
                const code = (codeAndKey[0] || '').split('&')[0];
                const keyB64 = codeAndKey[1] ? decodeURIComponent(codeAndKey[1]) : null;
                if (code) {
                    try {
                        const response = await postJson(`${SESSIONS_API}/join`, {
                            inviteCode: code,
                            encryptedSessionKey: null
                        });
                        if (response.ok) {
                            const joined = await response.json();
                            if (keyB64) {
                                await TeilenCrypto.setSessionKey(joined.id, TeilenCrypto.unb64(keyB64));
                            }
                            toast('Auto-joined room: ' + joined.name, 'success');
                            await loadSessions();
                            await selectSession(joined.id);
                        }
                    } catch (e) {}
                }
            }
            history.replaceState(null, '', location.pathname);
        }
    }

    // ---------- Pre-Send TTL Selection (Task 1) ----------

    document.querySelectorAll('.ttl-pill').forEach(pill => {
        if (pill.id === 'ttl-custom-pill') return;
        pill.addEventListener('click', () => {
            document.querySelectorAll('.ttl-pill').forEach(p => p.classList.remove('active'));
            pill.classList.add('active');
            selectedTtlSeconds = parseInt(pill.dataset.ttl, 10);
            ttlCustomPill.textContent = 'Custom';
        });
    });

    ttlCustomPill.addEventListener('click', () => {
        activeCustomTtlItemId = null; // Setting composer TTL
        customTtlTitle.textContent = 'Set Expiration Timer (TTL)';
        openModal(modalCustomTtl);
    });

    formCustomTtl.addEventListener('submit', async (e) => {
        e.preventDefault();
        const count = parseInt(customTtlNumber.value, 10) || 1;
        const multiplier = parseInt(customTtlUnit.value, 10) || 3600;
        let totalSec = count * multiplier;
        // Clamp between 10s and 86400s (backend valid TTL limits)
        totalSec = Math.max(10, Math.min(86400, totalSec));

        closeModal(modalCustomTtl);

        if (activeCustomTtlItemId) {
            // In-Feed Card TTL update
            await updateCardTtl(activeCustomTtlItemId, totalSec);
            activeCustomTtlItemId = null;
        } else {
            // Composer TTL update
            selectedTtlSeconds = totalSec;
            document.querySelectorAll('.ttl-pill').forEach(p => p.classList.remove('active'));
            ttlCustomPill.classList.add('active');
            ttlCustomPill.textContent = `Custom (${formatDurationShort(totalSec)})`;
            toast(`TTL set to ${formatDurationShort(totalSec)}`, 'info');
        }
    });

    function formatDurationShort(sec) {
        if (sec < 3600) return Math.round(sec / 60) + 'm';
        if (sec < 86400) return (sec / 3600).toFixed(1).replace('.0', '') + 'h';
        return (sec / 86400).toFixed(1).replace('.0', '') + 'd';
    }

    // ---------- Feed Cards with Sticky Top Header Bar (Tasks 1, 2, 3) ----------

    const BLOB_TYPES = new Set(['FILE', 'IMAGE', 'AUDIO', 'PDF']);

    function formatSize(bytes) {
        if (!bytes && bytes !== 0) return '';
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(0) + ' KB';
        if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
        return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
    }

    function upsert(item, isNew) {
        if (cards.has(item.id)) return;

        const li = document.createElement('li');
        li.className = 'card' + (isNew ? ' new' : '');
        li.dataset.id = item.id;

        // Sticky Top Action Header (Zero buttons at the bottom!)
        const head = document.createElement('div');
        head.className = 'card-head';

        // Left Header: Icon + Title Snippet + Route + Time
        const headLeft = document.createElement('div');
        headLeft.className = 'card-head-left';

        const badge = document.createElement('span');
        badge.className = 'badge ' + item.type.toLowerCase();
        badge.textContent = badgeLabel(item);

        const titleSnippet = document.createElement('span');
        titleSnippet.className = 'card-title-snippet';
        titleSnippet.textContent = item.content ? item.content.slice(0, 30) : '';

        const isLan = !!(item.isLan || String(item.id).startsWith('lan-'));
        const routePill = document.createElement('span');
        routePill.className = 'route-pill ' + (isLan ? 'lan' : 'relay');
        routePill.innerHTML = isLan
            ? `<svg viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="#10B981" stroke-width="2.5"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"></polygon></svg> Direct LAN`
            : `<svg viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 10h-1.26A8 8 0 1 0 9 20h9a5 5 0 0 0 0-10z"></path></svg> Relay`;

        const at = document.createElement('span');
        at.className = 'card-time';
        at.textContent = new Date(item.createdAt).toLocaleTimeString([], {hour: '2-digit', minute: '2-digit'});

        headLeft.append(badge, titleSnippet, routePill, at);

        // Right Header: All Action Buttons (TTL, +1h/Edit, Copy/Download, Expand, Delete)
        const headRight = document.createElement('div');
        headRight.className = 'card-head-right';

        // 1. Circular TTL Countdown Ring
        const clock = document.createElement('span');
        clock.className = 'ttl';
        const ring = document.createElementNS(SVG_NS, 'svg');
        ring.setAttribute('class', 'ring');
        ring.setAttribute('viewBox', '0 0 36 36');
        ring.setAttribute('width', '24');
        ring.setAttribute('height', '24');
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

        // 2. +1h / Edit Timer Button
        const extendBtn = document.createElement('button');
        extendBtn.type = 'button';
        extendBtn.className = 'btn-head-action';
        extendBtn.title = 'Click for +1h, right-click/long-press for custom TTL';
        extendBtn.textContent = '+1h';
        extendBtn.hidden = isLan;
        extendBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            extendItem(item.id);
        });
        extendBtn.addEventListener('contextmenu', (e) => {
            e.preventDefault();
            activeCustomTtlItemId = item.id;
            customTtlTitle.textContent = 'Edit Timer (TTL)';
            openModal(modalCustomTtl);
        });

        // 3. Copy (for TEXT/LINK) or Download (for BLOB)
        let mainActionBtn = document.createElement('button');
        mainActionBtn.type = 'button';
        mainActionBtn.className = 'btn-head-action';

        const href = item.blobUrl || (API + '/' + item.id + '/blob');

        if (BLOB_TYPES.has(item.type)) {
            mainActionBtn.innerHTML = `<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="7 10 12 15 17 10"></polyline><line x1="12" y1="15" x2="12" y2="3"></line></svg> <span>Download</span>`;
            mainActionBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                triggerBlobDownload(item, href);
            });
        } else {
            mainActionBtn.innerHTML = `<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg> <span>Copy</span>`;
            mainActionBtn.addEventListener('click', async (e) => {
                e.stopPropagation();
                await navigator.clipboard.writeText(item.content);
                mainActionBtn.classList.add('copied');
                toast('Copied to clipboard', 'success');
                setTimeout(() => mainActionBtn.classList.remove('copied'), 1500);
            });
        }

        // 4. Code Toggle & Expand Toggle (for TEXT)
        let codeToggleBtn = null;
        let expandBtn = null;
        let textContainer = null;

        if (item.type === 'TEXT') {
            codeToggleBtn = document.createElement('button');
            codeToggleBtn.type = 'button';
            codeToggleBtn.className = 'btn-head-action';
            codeToggleBtn.title = 'Toggle Monospace / Code view';
            codeToggleBtn.textContent = '<> Code';
            codeToggleBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                if (textContainer) {
                    textContainer.classList.toggle('code-mode');
                }
            });

            expandBtn = document.createElement('button');
            expandBtn.type = 'button';
            expandBtn.className = 'btn-head-action';
            expandBtn.title = 'Toggle Full View';
            expandBtn.textContent = '⤢ Expand';
            expandBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                if (textContainer) {
                    textContainer.classList.toggle('expanded');
                    expandBtn.textContent = textContainer.classList.contains('expanded') ? '⤡ Shrink' : '⤢ Expand';
                }
            });
        }

        // 5. Share Button
        const shareBtn = document.createElement('button');
        shareBtn.type = 'button';
        shareBtn.className = 'btn-head-action';
        shareBtn.title = 'Share item';
        shareBtn.innerHTML = `<svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2"><circle cx="18" cy="5" r="3"></circle><circle cx="6" cy="12" r="3"></circle><circle cx="18" cy="19" r="3"></circle><line x1="8.59" y1="13.51" x2="15.42" y2="17.49"></line><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"></line></svg>`;
        shareBtn.addEventListener('click', async (e) => {
            e.stopPropagation();
            if (navigator.share) {
                try {
                    await navigator.share({
                        title: 'Teilen Share',
                        text: item.content,
                        url: BLOB_TYPES.has(item.type) ? href : undefined
                    });
                    return;
                } catch (err) {}
            }
            await navigator.clipboard.writeText(BLOB_TYPES.has(item.type) ? href : item.content);
            toast('Share link copied to clipboard', 'success');
        });

        // 6. Delete Button (✕)
        const deleteBtn = document.createElement('button');
        deleteBtn.type = 'button';
        deleteBtn.className = 'btn-card-delete';
        deleteBtn.title = 'Delete this item';
        deleteBtn.innerHTML = `<svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="#EF4444" stroke-width="2.2"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>`;
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

        headRight.append(clock, extendBtn, mainActionBtn);
        if (codeToggleBtn) headRight.append(codeToggleBtn);
        if (expandBtn) headRight.append(expandBtn);
        headRight.append(shareBtn, deleteBtn);
        head.append(headLeft, headRight);

        // Card Body (Unlimited Scrollable Container - ZERO buttons at bottom!)
        const body = document.createElement('div');
        body.className = 'card-body';

        if (item.type === 'LINK') {
            const a = document.createElement('a');
            a.href = item.content;
            a.target = '_blank';
            a.rel = 'noopener noreferrer';
            a.textContent = item.content;
            a.className = 'link-content';
            body.append(a);
        } else if (BLOB_TYPES.has(item.type)) {
            body.append(renderBlobBody(item, href));
        } else {
            // Unlimited Scrollable Text (Task 2)
            textContainer = document.createElement('div');
            textContainer.className = 'card-text-scroll';
            textContainer.textContent = item.content;

            const bottomFade = document.createElement('div');
            bottomFade.className = 'card-bottom-fade';

            body.append(textContainer, bottomFade);
        }

        li.append(head, body);
        feed.prepend(li);
        cards.set(item.id, {item, element: li, ttl: ttlText, ring: fill});
        setTimeout(() => li.classList.remove('new'), 1500);
        refresh();
    }

    function renderBlobBody(item, href) {
        const wrap = document.createElement('div');
        wrap.className = 'file';

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
        } else if (item.type === 'AUDIO') {
            const audio = document.createElement('audio');
            audio.className = 'file-audio';
            audio.controls = true;
            audio.src = href;
            wrap.append(audio);
        }
        return wrap;
    }

    function badgeLabel(item) {
        if (item.type === 'LINK') return 'link';
        if (item.type === 'IMAGE') return 'image';
        if (item.type === 'PDF') return 'pdf';
        if (item.type === 'AUDIO') return 'audio';
        if (item.type === 'FILE') return 'file';
        return 'text';
    }

    async function triggerBlobDownload(item, href) {
        try {
            toast('Downloading ' + (item.content || 'file') + '…', 'info');
            const resp = await fetch(href);
            if (!resp.ok) throw new Error('Download failed');
            const blob = await resp.blob();
            const blobUrl = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = blobUrl;
            a.download = item.content || 'file';
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            setTimeout(() => URL.revokeObjectURL(blobUrl), 10000);
            toast('Downloaded ' + (item.content || 'file'), 'success');
        } catch (err) {
            const a = document.createElement('a');
            a.href = href;
            a.download = item.content || 'file';
            a.target = '_blank';
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
        }
    }

    async function extendItem(id) {
        try {
            const response = await api('/api/v1/items/' + id + '/extend', {method: 'POST'});
            if (!response.ok) {
                toast('Could not extend: ' + (await why(response)), 'error');
                return;
            }
            applyExtended(await response.json());
            toast('Extended +1 hour', 'success');
        } catch (e) {
            toast('Failed to extend lifespan', 'error');
        }
    }

    async function updateCardTtl(id, ttlSeconds) {
        try {
            const response = await api('/api/v1/items/' + id + '/ttl', {
                method: 'PUT',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({ttlSeconds})
            });
            if (!response.ok) {
                toast('Could not update timer: ' + (await why(response)), 'error');
                return;
            }
            applyExtended(await response.json());
            toast(`Timer updated (${formatDurationShort(ttlSeconds)})`, 'success');
        } catch (e) {
            toast('Failed to update timer', 'error');
        }
    }

    function applyExtended(item) {
        const entry = cards.get(item.id);
        if (!entry) return;
        entry.item = item;
        entry.ttl.textContent = formatLeft(remaining(item));
        refresh();
    }

    function remove(id) {
        const entry = cards.get(id);
        if (!entry) return;
        cards.delete(id);
        entry.element.classList.add('leaving');
        setTimeout(() => {
            entry.element.remove();
            refresh();
        }, 300);
    }

    function refresh() {
        const count = cards.size;
        countEl.textContent = count === 1 ? '1 active item' : count + ' active items';
        empty.hidden = count > 0;
        clearBtn.hidden = count === 0;
    }

    function remaining(item) {
        return Math.max(0, Math.round((new Date(item.expiresAt).getTime() - Date.now()) / 1000));
    }

    function formatLeft(seconds) {
        if (seconds <= 0) return 'expired';
        const h = Math.floor(seconds / 3600);
        const m = Math.floor((seconds % 3600) / 60);
        const s = seconds % 60;
        if (h > 0) return h + 'h ' + String(m).padStart(2, '0') + 'm';
        return m > 0 ? m + 'm ' + String(s).padStart(2, '0') + 's' : s + 's';
    }

    function totalSeconds(item) {
        return Math.max(1, Math.round((new Date(item.expiresAt).getTime() - new Date(item.createdAt).getTime()) / 1000));
    }

    // Tick countdown rings
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
                entry.ring.classList.toggle('warn', fraction <= 0.20 && fraction > 0.05);
                entry.ring.classList.toggle('danger', fraction <= 0.05);
            }
            if (left <= 0) {
                remove(id);
            }
        }
    }, 1000);

    // ---------- Composer & Sending (Tasks 1, 2, 5) ----------

    function isUrl(text) {
        return /^https?:\/\/\S+$/i.test(text.trim());
    }

    // Unlimited Text Composer Submit
    compose.addEventListener('submit', async (e) => {
        e.preventDefault();
        const content = composeInput.value.trim();
        if (!content) return;

        composeInput.value = '';
        toast('Encrypting & sending…', 'info');

        const type = isUrl(content) ? 'LINK' : 'TEXT';
        const response = await postJson(API, {
            content,
            type,
            ttlSeconds: selectedTtlSeconds,
            sessionId: currentSessionId
        });

        if (response.ok) {
            toast('Sent', 'success');
        } else {
            const message = await why(response);
            toast('Could not send: ' + message, 'error');
            composeInput.value = content;
        }
    });

    // Enter submits, Shift+Enter adds newline
    composeInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            compose.dispatchEvent(new Event('submit', {cancelable: true}));
        }
    });

    // File attachments with Multi-GB Tiered Policy (Task 5)
    uploadInput.addEventListener('change', async () => {
        const files = [...uploadInput.files];
        uploadInput.value = '';
        if (files.length === 0) return;

        for (const file of files) {
            if (file.size > MAX_RELAY_BYTES) {
                // Multi-GB Direct LAN P2P
                await sendMultiGbLan(file);
            } else {
                // Standard Cloud Relay upload
                await uploadCloudFile(file);
            }
        }
    });

    async function uploadCloudFile(file) {
        toast(`Uploading ${file.name}…`, 'info');
        const body = new FormData();
        body.append('file', file);
        body.append('ttlSeconds', selectedTtlSeconds);
        if (currentSessionId) {
            body.append('sessionId', currentSessionId);
        }

        const response = await api(API, {method: 'POST', body});
        if (response.ok) {
            toast(`Sent ${file.name}`, 'success');
        } else {
            toast(`Failed to send ${file.name}: ` + (await why(response)), 'error');
        }
    }

    async function sendMultiGbLan(file) {
        toast(`⚡ Initiating Direct LAN P2P for ${file.name} (${formatSize(file.size)})`, 'info');
        showTelemetry({
            direction: 'tx',
            fileName: file.name,
            bytesSent: 0,
            totalBytes: file.size,
            percent: 0,
            speedMBps: '0.0'
        });

        activeLanTransfer = TeilenLan.sendFile(
            file,
            (progress) => {
                showTelemetry({
                    direction: 'tx',
                    fileName: file.name,
                    bytesSent: progress.bytesSent,
                    totalBytes: progress.totalBytes,
                    percent: progress.percent,
                    speedMBps: progress.speedMBps
                });
            },
            () => {
                toast(`⚡ Direct LAN transfer complete: ${file.name}`, 'success');
                hideTelemetry();
            },
            (err) => {
                hideTelemetry();
                toast('Direct LAN Transfer Notice: ' + err, 'warn');
                toast('Files over 30 MB transfer directly device-to-device for free without server storage. Make sure the receiving device is open on the same Wi-Fi/Hotspot.', 'info', 7000);
            }
        );
    }

    // Telemetry UI
    function showTelemetry(info) {
        if (!info) {
            hideTelemetry();
            return;
        }
        telemetryCard.hidden = false;
        telemetryName.textContent = info.fileName;
        telemetryBar.style.width = info.percent + '%';
        const sent = formatSize(info.bytesSent || info.bytesReceived || 0);
        const total = formatSize(info.totalBytes || 0);
        telemetryStats.textContent = `${sent} / ${total} · ${info.percent}%`;
        telemetrySpeed.textContent = `${info.speedMBps} MB/s`;
    }

    function hideTelemetry() {
        telemetryCard.hidden = true;
        activeLanTransfer = null;
    }

    telemetryCancel.addEventListener('click', () => {
        if (activeLanTransfer) {
            activeLanTransfer.cancel();
            hideTelemetry();
            toast('LAN transfer cancelled', 'info');
        }
    });

    // Global Paste (Ctrl+V)
    window.addEventListener('paste', (event) => {
        if (!token.get()) return;
        const target = event.target;
        if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA')) return;

        const items = event.clipboardData && event.clipboardData.items;
        if (!items) return;

        const files = [];
        for (const item of items) {
            if (item.kind === 'file') {
                const file = item.getAsFile();
                if (file) files.push(file);
            } else if (item.kind === 'string' && item.type === 'text/plain') {
                item.getAsString(async (value) => {
                    const text = (value || '').trim();
                    if (text) {
                        toast('Pasted text — encrypting & sending…', 'info');
                        await postJson(API, {
                            content: text,
                            type: isUrl(text) ? 'LINK' : 'TEXT',
                            ttlSeconds: selectedTtlSeconds,
                            sessionId: currentSessionId
                        });
                    }
                });
            }
        }
        if (files.length) {
            event.preventDefault();
            files.forEach(f => f.size > MAX_RELAY_BYTES ? sendMultiGbLan(f) : uploadCloudFile(f));
        }
    });

    // Drag-and-drop
    let dragDepth = 0;
    window.addEventListener('dragenter', () => {
        if (!token.get()) return;
        dragDepth++;
        dropVeil.hidden = false;
    });
    window.addEventListener('dragover', (e) => {
        if (token.get()) e.preventDefault();
    });
    window.addEventListener('dragleave', () => {
        dragDepth = Math.max(0, dragDepth - 1);
        if (dragDepth === 0) dropVeil.hidden = true;
    });
    window.addEventListener('drop', (e) => {
        dragDepth = 0;
        dropVeil.hidden = true;
        if (!token.get() || !e.dataTransfer) return;
        e.preventDefault();
        const files = [...e.dataTransfer.files];
        if (files.length) {
            files.forEach(f => f.size > MAX_RELAY_BYTES ? sendMultiGbLan(f) : uploadCloudFile(f));
        }
    });

    // Purge All
    clearBtn.addEventListener('click', async () => {
        if (cards.size === 0) return;
        const toDelete = [...cards.keys()];
        toDelete.forEach(id => remove(id));
        toast('Purging ephemeral feed…', 'info');
        await Promise.all(toDelete.map(id => {
            if (id.startsWith('lan-')) return Promise.resolve();
            return api(API + '/' + id, {method: 'DELETE'}).catch(() => {});
        }));
    });

    // ---------- WebSocket Feed & Polling ----------

    async function load() {
        try {
            const path = currentSessionId ? `${API}?sessionId=${encodeURIComponent(currentSessionId)}` : API;
            const response = await api(path);
            if (!response.ok) throw new Error(await why(response));
            const items = await response.json();
            items.reverse().forEach(item => upsert(item, false));
        } catch (e) {
            console.warn('Load failed', e);
        }
    }

    function connect() {
        stopSocket();
        const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
        let wsUrl = `${scheme}://${location.host}/ws?token=${encodeURIComponent(token.get() || '')}`;
        if (currentSessionId) wsUrl += `&sessionId=${encodeURIComponent(currentSessionId)}`;

        const live = new WebSocket(wsUrl);
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
                if (!event.item.sessionId || event.item.sessionId === currentSessionId) {
                    upsert(event.item, true);
                }
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
        if (old) old.close();
    }

    function startPolling() {
        if (pollTimer) return;
        pollTimer = setInterval(async () => {
            const ids = new Set();
            try {
                const path = currentSessionId ? `${API}?sessionId=${encodeURIComponent(currentSessionId)}` : API;
                const response = await api(path);
                const items = await response.json();
                items.forEach(item => {
                    ids.add(item.id);
                    upsert(item, false);
                });
            } catch (e) {}
            for (const id of [...cards.keys()]) {
                if (!ids.has(id)) remove(id);
            }
        }, 3000);
    }

    function stopPolling() {
        clearInterval(pollTimer);
        pollTimer = null;
    }

    function setStatus(text, kind) {
        const statusText = el('status-text');
        if (statusText) statusText.textContent = text;
        if (statusEl) {
            statusEl.className = 'status ' + kind;
        }
    }

    // ---------- Devices & Account Section ----------

    function renderDevices(devices) {
        // Hero Pairing Card: show when user has <= 1 connected device, hide when >= 2 connected devices
        if (heroPairingCard) {
            const hasMultiple = devices && devices.length > 1;
            if (hasMultiple || heroPairingDismissed) {
                heroPairingCard.hidden = true;
            } else {
                heroPairingCard.hidden = false;
                startPairing();
            }
        }

        deviceList.replaceChildren(...devices.map(device => {
            const row = document.createElement('li');
            row.className = 'device';

            const label = document.createElement('span');
            label.className = 'device-name';
            label.textContent = device.name + (device.current ? ' (this browser)' : '');

            const meta = document.createElement('span');
            meta.className = 'device-meta';
            const details = [];
            if (device.platform) details.push(device.platform.slice(0, 30));
            if (device.ipAddress) details.push(device.ipAddress);
            details.push(new Date(device.lastSeenAt).toLocaleDateString());
            meta.textContent = details.join(' · ');

            row.append(label, meta);

            if (!device.current) {
                const dropBtn = document.createElement('button');
                dropBtn.className = 'btn-device-drop';
                dropBtn.textContent = 'Revoke';
                dropBtn.type = 'button';
                dropBtn.addEventListener('click', async () => {
                    dropBtn.disabled = true;
                    const res = await api('/api/devices/' + device.id, {method: 'DELETE'});
                    if (res.ok) {
                        toast('Device revoked', 'info');
                        row.remove();
                    } else {
                        dropBtn.disabled = false;
                        toast('Could not revoke device', 'error');
                    }
                });
                row.append(dropBtn);
            }
            return row;
        }));
    }

    // Revoke all other devices (Task 6.3)
    if (revokeOthersBtn) {
        revokeOthersBtn.addEventListener('click', async () => {
            if (!confirm('Sign out and revoke all other devices?')) return;
            const res = await api('/api/devices/others', {method: 'DELETE'});
            if (res.ok) {
                toast('All other devices revoked', 'success');
                const devRes = await api('/api/devices');
                if (devRes.ok) renderDevices(await devRes.json());
            } else {
                toast('Failed to revoke other devices', 'error');
            }
        });
    }

    // Sign out
    signOutBtn.addEventListener('click', async () => {
        await api('/api/auth/logout', {method: 'POST'});
        token.set(null);
        showPairing();
        toast('Signed out', 'info');
    });

    // Account menu toggle
    accountBtn.addEventListener('click', async () => {
        const wasOpen = !deviceSection.hidden;
        if (wasOpen) {
            deviceSection.hidden = true;
            accountBtn.setAttribute('aria-expanded', 'false');
            return;
        }
        deviceSection.hidden = false;
        accountBtn.setAttribute('aria-expanded', 'true');
        const res = await api('/api/devices');
        if (res.ok) renderDevices(await res.json());
    });

    // ---------- Auth, Login & Pairing ----------

    function showPairing() {
        stopSocket();
        stopPolling();
        TeilenLan.stop();
        cards.clear();
        feed.replaceChildren();
        pairSection.hidden = false;
        app.hidden = true;
        foot.hidden = true;
        deviceSection.hidden = true;
        accountBtn.hidden = true;
        setStatus('offline', 'offline');
        startPairing();
    }

    async function showFeed() {
        pairSection.hidden = true;
        app.hidden = false;
        foot.hidden = false;
        accountBtn.hidden = false;

        await loadSessions();
        await load();
        connect();

        try {
            const devRes = await api('/api/devices');
            if (devRes.ok) {
                renderDevices(await devRes.json());
            }
        } catch (e) {
            console.warn('Could not load devices:', e);
        }

        // Start LAN WebRTC engine with session scoping and telemetry handler
        TeilenLan.start(token.get(), (lanItem) => {
            upsert(lanItem, true);
            toast('Received Direct LAN file: ' + lanItem.content, 'success');
        }, showTelemetry, currentSessionId);

        await checkUrlForInvite();
    }

    // Instant Zero-Auth Vault Provisioning
    async function silentProvisionVault() {
        try {
            toast('Initializing instant vault…', 'info');
            await TeilenCrypto.ensureMasterKey();
            const identity = await TeilenCrypto.ensureIdentity();
            const randChars = Math.random().toString(36).substring(2, 8).toLowerCase();
            const username = 'vault-' + randChars;
            const password = Array.from(crypto.getRandomValues(new Uint8Array(16)))
                .map(b => b.toString(16).padStart(2, '0')).join('');
            const deviceName = navigator.userAgent.includes('Chrome') ? 'Chrome' :
                navigator.userAgent.includes('Firefox') ? 'Firefox' :
                navigator.userAgent.includes('Safari') ? 'Safari' : 'Browser';

            const res = await fetch('/api/auth/account', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                credentials: 'same-origin',
                body: JSON.stringify({
                    username,
                    password,
                    displayName: 'Personal Vault',
                    deviceName,
                    deviceType: 'WEB',
                    devicePublicKey: TeilenCrypto.b64(identity.publicKey)
                })
            });

            if (res.ok) {
                const session = await res.json();
                token.set(session.token);
                toast('Instant vault initialized (' + username + ')', 'success');
                await showFeed();
                return true;
            } else {
                toast('Provisioning failed: ' + (await why(res)), 'error');
                return false;
            }
        } catch (err) {
            console.error('Silent provisioning error:', err);
            toast('Failed to create instant vault: ' + err.message, 'error');
            return false;
        }
    }

    if (btnInstantVault) {
        btnInstantVault.addEventListener('click', () => {
            silentProvisionVault();
        });
    }

    if (btnToggleAuthAdvanced) {
        btnToggleAuthAdvanced.addEventListener('click', () => {
            const hidden = authAdvancedContainer.hidden;
            authAdvancedContainer.hidden = !hidden;
            if (authAdvancedChevron) authAdvancedChevron.textContent = hidden ? '▴' : '▾';
        });
    }

    // Tab switching for Auth
    if (authTabLogin) {
        authTabLogin.addEventListener('click', () => {
            authTabLogin.classList.add('active');
            if (authTabRegister) authTabRegister.classList.remove('active');
            if (authTabPair) authTabPair.classList.remove('active');
            loginContainer.hidden = false;
            registerContainer.hidden = true;
            if (pairContainer) pairContainer.hidden = false; // Pairing stays visible in main body
        });
    }

    if (authTabRegister) {
        authTabRegister.addEventListener('click', () => {
            authTabRegister.classList.add('active');
            if (authTabLogin) authTabLogin.classList.remove('active');
            if (authTabPair) authTabPair.classList.remove('active');
            registerContainer.hidden = false;
            loginContainer.hidden = true;
            if (pairContainer) pairContainer.hidden = false; // Pairing stays visible in main body
        });
    }

    if (authTabPair) {
        authTabPair.addEventListener('click', () => {
            authTabPair.classList.add('active');
            if (authTabLogin) authTabLogin.classList.remove('active');
            if (authTabRegister) authTabRegister.classList.remove('active');
            pairContainer.hidden = false;
            loginContainer.hidden = true;
            registerContainer.hidden = true;
            startPairing();
        });
    }

    loginForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        const username = loginUsername.value.trim();
        const password = loginPassword.value;
        const identity = await TeilenCrypto.ensureIdentity();

        loginStatus.hidden = false;
        loginStatus.textContent = 'Signing in…';

        const res = await fetch('/api/auth/login', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            credentials: 'same-origin',
            body: JSON.stringify({
                username,
                password,
                deviceName: navigator.userAgent.includes('Chrome') ? 'Chrome' : 'Browser',
                deviceType: 'WEB',
                devicePublicKey: TeilenCrypto.b64(identity.publicKey)
            })
        });

        if (res.ok) {
            const session = await res.json();
            token.set(session.token);
            toast('Signed in as ' + username, 'success');
            await showFeed();
        } else {
            loginStatus.textContent = 'Sign in failed: ' + (await why(res));
        }
    });

    registerForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        const username = registerUsername.value.trim();
        const password = registerPassword.value;
        const displayName = registerDisplayName.value.trim() || undefined;
        const identity = await TeilenCrypto.ensureIdentity();

        registerStatus.hidden = false;
        registerStatus.textContent = 'Creating account…';

        const res = await fetch('/api/auth/account', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            credentials: 'same-origin',
            body: JSON.stringify({
                username,
                password,
                displayName,
                deviceName: navigator.userAgent.includes('Chrome') ? 'Chrome' : 'Browser',
                deviceType: 'WEB',
                devicePublicKey: TeilenCrypto.b64(identity.publicKey)
            })
        });

        if (res.ok) {
            const session = await res.json();
            token.set(session.token);
            toast('Account created!', 'success');
            await showFeed();
        } else {
            registerStatus.textContent = 'Registration failed: ' + (await why(res));
        }
    });

    async function startPairing() {
        clearInterval(pairTimer);
        pairKey = TeilenCrypto.newPairingKey();
        try {
            const res = await fetch('/api/auth/device/code', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({
                    deviceName: 'Browser',
                    deviceType: 'WEB',
                    platform: navigator.platform || 'Web',
                    publicKey: TeilenCrypto.b64(pairKey.publicKey)
                })
            });
            if (!res.ok) return;
            const data = await res.json();
            pairUserCode = data.userCode;

            // Update main pair section
            if (pairCode) pairCode.textContent = data.userCode;
            if (qr) qr.src = data.qrCodeUrl;
            if (pairState) pairState.textContent = 'Scan or approve code on phone…';

            // Update hero card
            if (heroQr) heroQr.src = data.qrCodeUrl;
            if (heroPairCode) heroPairCode.textContent = data.userCode;
            if (heroPairStatus) heroPairStatus.textContent = 'Scan with phone or enter code below…';

            // Update modal
            if (modalQr) modalQr.src = data.qrCodeUrl;
            if (modalPairCode) modalPairCode.textContent = data.userCode;
            if (modalPairStatus) modalPairStatus.textContent = 'Scan with phone or enter code below…';

            pairTimer = setInterval(async () => {
                try {
                    const claimRes = await fetch('/api/auth/device/token', {
                        method: 'POST',
                        headers: {'Content-Type': 'application/json'},
                        credentials: 'same-origin',
                        body: JSON.stringify({deviceCode: data.deviceCode})
                    });
                    if (claimRes.status === 200) {
                        clearInterval(pairTimer);
                        const claim = await claimRes.json();
                        if (claim.keyPackage && pairKey) {
                            await TeilenCrypto.openFromApprover(claim.keyPackage, pairKey.privateKey, pairUserCode);
                        }
                        token.set(claim.token);
                        toast('Pairing successful!', 'success');
                        closeModal(modalPairPhone);
                        await showFeed();
                    }
                } catch (e) {
                    console.warn('Pairing poll error:', e);
                }
            }, data.pollIntervalSeconds * 1000);
        } catch (e) {
            console.warn('Pairing failed', e);
        }
    }

    const copyPairCode = async () => {
        if (pairUserCode) {
            await navigator.clipboard.writeText(pairUserCode);
            toast('Pairing code copied', 'success');
        }
    };
    if (pairCodeCopy) pairCodeCopy.addEventListener('click', copyPairCode);
    if (heroCodeCopy) heroCodeCopy.addEventListener('click', copyPairCode);
    if (modalPairCodeCopy) modalPairCodeCopy.addEventListener('click', copyPairCode);

    if (btnHeaderPair) {
        btnHeaderPair.addEventListener('click', () => {
            openModal(modalPairPhone);
            startPairing();
        });
    }

    if (heroPairingDismiss) {
        heroPairingDismiss.addEventListener('click', () => {
            heroPairingDismissed = true;
            if (heroPairingCard) heroPairingCard.hidden = true;
        });
    }

    async function handleApprove(code, statusEl, inputEl) {
        if (!code) return;
        if (statusEl) {
            statusEl.hidden = false;
            statusEl.textContent = 'Approving device…';
        }
        try {
            const pendingRes = await api('/api/auth/device/pending?code=' + encodeURIComponent(code));
            if (!pendingRes.ok) {
                if (statusEl) statusEl.textContent = 'Invalid code: ' + (await why(pendingRes));
                return;
            }
            const preview = await pendingRes.json();
            let keyPkg = null;
            if (preview.publicKey) {
                keyPkg = await TeilenCrypto.sealForNewcomer(preview.publicKey, code);
            }

            const approveRes = await postJson('/api/auth/device/approve', {
                userCode: code,
                keyPackage: keyPkg
            });

            if (approveRes.ok) {
                if (inputEl) inputEl.value = '';
                if (statusEl) statusEl.textContent = 'Device connected!';
                toast('Phone successfully paired!', 'success');
                const devRes = await api('/api/devices');
                if (devRes.ok) renderDevices(await devRes.json());
                closeModal(modalPairPhone);
            } else {
                if (statusEl) statusEl.textContent = 'Approval failed: ' + (await why(approveRes));
            }
        } catch (err) {
            if (statusEl) statusEl.textContent = 'Error: ' + err.message;
        }
    }

    approveForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        await handleApprove(approveCode.value.trim(), deviceStatus, approveCode);
    });

    if (heroApproveForm && heroApproveCode) {
        heroApproveForm.addEventListener('submit', async (e) => {
            e.preventDefault();
            await handleApprove(heroApproveCode.value.trim(), heroApproveStatus, heroApproveCode);
        });
    }

    if (modalApproveForm && modalApproveCode) {
        modalApproveForm.addEventListener('submit', async (e) => {
            e.preventDefault();
            await handleApprove(modalApproveCode.value.trim(), modalApproveStatus, modalApproveCode);
        });
    }

    // Brand launch animation
    function initLaunchScreen() {
        const veil = el('launch-veil');
        const launchStatus = el('launch-status');
        if (!veil) return;
        setTimeout(() => {
            if (launchStatus) {
                launchStatus.style.opacity = '0';
                setTimeout(() => {
                    launchStatus.textContent = 'Syncing Ephemeral Feed…';
                    launchStatus.style.opacity = '1';
                }, 120);
            }
        }, 650);
        setTimeout(() => {
            veil.classList.add('dismissed');
            veil.hidden = true;
            setTimeout(() => {
                try { veil.remove(); } catch (e) {}
            }, 300);
        }, 1200);
    }

    // Auto-update SW
    function initServiceWorkerAutoUpdate() {
        if (!('serviceWorker' in navigator)) return;
        navigator.serviceWorker.register('/sw.js').catch(() => {});
    }

    function enablePush() {
        // Handled by push service worker if subscribed
    }

    // Initialize SPA
    initLaunchScreen();
    initServiceWorkerAutoUpdate();
    refresh();

    async function initApp() {
        try {
            const authenticated = await trySilentRefresh();
            if (authenticated) {
                await showFeed();
                return;
            }
            // Auto silent-provision a zero-auth vault
            const provisioned = await silentProvisionVault();
            if (!provisioned) {
                showPairing();
            }
        } catch (err) {
            console.error('Startup initialization error:', err);
            token.set(null);
            showPairing();
        }
    }

    initApp();
})();

/* Teilen feed: load once, then live over a websocket, everything expires itself. */
(() => {
    'use strict';

    const API = '/api/items';
    const feed = document.getElementById('feed');
    const empty = document.getElementById('empty');
    const statusEl = document.getElementById('status');
    const countEl = document.getElementById('count');
    const clearBtn = document.getElementById('clear');
    const compose = document.getElementById('compose');
    const composeInput = document.getElementById('compose-input');

    /** id -> {item, element} */
    const cards = new Map();
    let socket = null;
    let retryDelay = 1000;
    let pollTimer = null;

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

    /** images preview inline, everything else opens in a new tab via the blob endpoint */
    function fileBody(item) {
        const wrap = document.createElement('div');
        wrap.className = 'file';

        const href = API + '/' + item.id + '/blob';
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

    // ---------- transport ----------

    function setStatus(text, cls) {
        statusEl.textContent = text;
        statusEl.className = 'status ' + cls;
    }

    async function load() {
        try {
            const response = await fetch(API);
            if (!response.ok) {
                throw new Error('HTTP ' + response.status);
            }
            const items = await response.json();
            // newest first
            items.reverse().forEach((item) => upsert(item, false));
        } catch (e) {
            console.warn('load failed', e);
        }
    }

    function connect() {
        const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
        socket = new WebSocket(scheme + '://' + location.host + '/ws');

        socket.addEventListener('open', () => {
            retryDelay = 1000;
            setStatus('live', 'live');
            stopPolling();
        });

        socket.addEventListener('message', (message) => {
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

        socket.addEventListener('close', () => {
            setStatus('reconnecting…', 'offline');
            startPolling();
            setTimeout(connect, retryDelay);
            retryDelay = Math.min(retryDelay * 2, 15000);
        });

        socket.addEventListener('error', () => socket.close());
    }

    // if the socket is down, the feed still updates by polling
    function startPolling() {
        if (pollTimer) {
            return;
        }
        pollTimer = setInterval(async () => {
            const ids = new Set();
            try {
                const response = await fetch(API);
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

    async function send(content) {
        const response = await fetch(API, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({content})
        });
        if (!response.ok) {
            const body = await response.json().catch(() => ({}));
            throw new Error(body.message || 'HTTP ' + response.status);
        }
    }

    compose.addEventListener('submit', async (e) => {
        e.preventDefault();
        const content = composeInput.value.trim();
        if (!content) {
            return;
        }
        composeInput.value = '';
        try {
            await send(content);
        } catch (err) {
            alert('could not send: ' + err.message);
            composeInput.value = content;
        }
    });

    // ---------- files ----------

    const upload = document.getElementById('upload');
    const uploadInput = document.getElementById('upload-input');
    const uploadLabel = document.getElementById('upload-label');
    const uploadTtl = document.getElementById('upload-ttl');
    const uploadSend = document.getElementById('upload-send');
    const uploadStatus = document.getElementById('upload-status');
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
        const names = files.map(f => f.name).join(', ');
        uploadLabel.textContent = files.length === 1 ? names : files.length + ' files selected';
        // check up front so an oversized file never leaves the browser
        const tooBig = files.filter(f => f.size > MAX_BYTES).map(f => f.name);
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
            try {
                const response = await fetch(API, {method: 'POST', body});
                if (!response.ok) {
                    const err = await response.json().catch(() => ({}));
                    throw new Error(err.message || 'HTTP ' + response.status);
                }
                sent++;
            } catch (err) {
                showUploadStatus(file.name + ': ' + err.message, true);
                uploadSend.disabled = false;
                return;
            }
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
            fetch(API + '/' + id, {method: 'DELETE'}).catch(() => {})));
    });

    load().then(connect);
    refresh();
})();

/*
 * The receiving end of the direct LAN fast-path.
 *
 * A paired phone offers a WebRTC data channel over the signaling relay; this side verifies the
 * offer's Ed25519 signature, answers, and then takes one secretstream envelope off the channel.
 * The envelope is opened by the same worker the feed already trusts, with the account key held in
 * this browser — the server sees none of it, and neither does the network beyond DTLS.
 *
 * It exposes:
 *   TeilenLan.start(token, onItem)   begin listening as this account
 *   TeilenLan.stop()                 tear everything down (sign-out)
 *
 * onItem(item) receives an item shaped exactly like a feed item, so the feed renders it the same
 * way whether it arrived over the cloud or straight from the phone.
 */
window.TeilenLan = (() => {
    'use strict';

    const SIGN_CONTEXT = 'teilen-webrtc-v1';
    const LOCAL_TTL_SECONDS = 600;

    let token = null;
    let onItem = null;
    let socket = null;
    let retryDelay = 1000;

    let peer = null;
    let channel = null;
    let chunks = [];
    let total = 0;
    let finished = false;

    let worker = null;
    let workerSeq = 0;

    function signingMessage(type, sdp) {
        return sodium.from_string([SIGN_CONTEXT, type, sdp].join('|'));
    }

    async function start(rawToken, handleItem) {
        token = rawToken || null;
        onItem = handleItem;
        if (!token || !('RTCPeerConnection' in window)) {
            return;
        }
        await sodium.ready;
        if (!worker) {
            worker = new Worker('decrypt.worker.js');
            worker.addEventListener('message', onDecrypted);
        }
        connect();
    }

    function stop() {
        token = null;
        onItem = null;
        closePeer();
        const old = socket;
        socket = null;
        if (old) {
            try {
                old.close();
            } catch (e) {
                /* already gone */
            }
        }
    }

    // ---------- signaling ----------

    function connect() {
        if (!token) {
            return;
        }
        const old = socket;
        socket = null;
        if (old) {
            try {
                old.close();
            } catch (e) {
                /* already gone */
            }
        }

        const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
        const live = new WebSocket(scheme + '://' + location.host + '/ws/signal?token='
            + encodeURIComponent(token));
        socket = live;

        live.addEventListener('open', () => {
            retryDelay = 1000;
        });
        live.addEventListener('message', (message) => {
            handleSignal(message.data);
        });
        live.addEventListener('close', () => {
            if (socket !== live) {
                return;
            }
            socket = null;
            if (!token) {
                return;
            }
            setTimeout(connect, retryDelay);
            retryDelay = Math.min(retryDelay * 2, 15000);
        });
        live.addEventListener('error', () => live.close());
    }

    async function handleSignal(text) {
        let message;
        try {
            message = JSON.parse(text);
        } catch (e) {
            return;
        }
        if (message.type === 'OFFER') {
            await acceptOffer(message);
        } else if (message.type === 'CANDIDATE' && peer) {
            try {
                await peer.addIceCandidate({
                    candidate: message.candidate,
                    sdpMid: message.sdpMid,
                    sdpMLineIndex: message.sdpMLineIndex
                });
            } catch (e) {
                /* a candidate that arrives too early is simply skipped */
            }
        } else if (message.type === 'BYE') {
            closePeer();
        }
    }

    async function acceptOffer(message) {
        if (!message.sdp || !message.signPk || !message.sig) {
            return;
        }
        const trusted = sodium.crypto_sign_verify_detached(
            TeilenCrypto.unb64(message.sig),
            signingMessage('OFFER', message.sdp),
            TeilenCrypto.unb64(message.signPk));
        if (!trusted) {
            console.warn('refused a WebRTC offer that was not signed by a paired phone');
            return;
        }
        if (!await TeilenCrypto.hasMasterKey()) {
            console.warn('no account key yet; the LAN offer has nothing to open with');
            return;
        }

        closePeer();
        finished = false;
        chunks = [];
        total = 0;

        peer = new RTCPeerConnection({iceServers: []});

        peer.addEventListener('icecandidate', (event) => {
            if (event.candidate && socket && socket.readyState === WebSocket.OPEN) {
                socket.send(JSON.stringify({
                    type: 'CANDIDATE',
                    candidate: event.candidate.candidate,
                    sdpMid: event.candidate.sdpMid,
                    sdpMLineIndex: event.candidate.sdpMLineIndex
                }));
            }
        });

        peer.addEventListener('datachannel', (event) => {
            channel = event.channel;
            channel.binaryType = 'arraybuffer';
            channel.addEventListener('message', onChannelMessage);
            channel.addEventListener('close', finish);
        });

        peer.addEventListener('connectionstatechange', () => {
            if (peer && (peer.connectionState === 'failed'
                || peer.connectionState === 'disconnected'
                || peer.connectionState === 'closed')) {
                closePeer();
            }
        });

        await peer.setRemoteDescription({type: 'offer', sdp: message.sdp});
        const answer = await peer.createAnswer();
        await peer.setLocalDescription(answer);

        const identity = await TeilenCrypto.ensureIdentity();
        const signature = sodium.crypto_sign_detached(
            signingMessage('ANSWER', answer.sdp), identity.privateKey);
        socket.send(JSON.stringify({
            type: 'ANSWER',
            sdp: answer.sdp,
            signPk: TeilenCrypto.b64(identity.publicKey),
            sig: TeilenCrypto.b64(signature)
        }));
    }

    // ---------- the envelope ----------

    function onChannelMessage(event) {
        if (event.data instanceof ArrayBuffer) {
            chunks.push(new Uint8Array(event.data));
            total += event.data.byteLength;
        } else if (typeof event.data === 'string' && event.data.indexOf('eof') !== -1) {
            finish();
        }
    }

    function finish() {
        if (finished) {
            return;
        }
        finished = true;
        if (!chunks.length) {
            closePeer();
            return;
        }
        const bytes = new Uint8Array(total);
        let offset = 0;
        for (const chunk of chunks) {
            bytes.set(chunk, offset);
            offset += chunk.length;
        }
        chunks = [];
        total = 0;
        closePeer();
        openEnvelope(bytes);
    }

    async function openEnvelope(bytes) {
        const masterKey = await TeilenCrypto.getMasterKey();
        if (!masterKey) {
            return;
        }
        worker.postMessage({
            id: 'lan-' + (++workerSeq),
            masterKey: masterKey,
            encrypted: bytes.buffer
        }, [bytes.buffer]);
    }

    function onDecrypted(event) {
        const {metadata, blob, error} = event.data;
        if (error) {
            console.warn('a LAN transfer did not open:', error);
            return;
        }
        if (!onItem) {
            return;
        }
        const now = Date.now();
        const mime = (metadata && metadata.type) || blob.type || 'application/octet-stream';
        onItem({
            id: 'lan-' + now + '-' + Math.random().toString(36).slice(2, 8),
            type: typeOf(mime),
            content: (metadata && metadata.name) || 'received file',
            mimeType: mime,
            sizeBytes: blob.size,
            createdAt: new Date(now).toISOString(),
            expiresAt: new Date(now + LOCAL_TTL_SECONDS * 1000).toISOString(),
            blobUrl: URL.createObjectURL(blob)
        }, true);
    }

    function typeOf(mime) {
        if (mime.indexOf('image/') === 0) {
            return 'IMAGE';
        }
        if (mime === 'application/pdf') {
            return 'PDF';
        }
        return 'FILE';
    }

    function closePeer() {
        finished = true;
        if (channel) {
            try {
                channel.close();
            } catch (e) {
                /* already closed */
            }
            channel = null;
        }
        if (peer) {
            try {
                peer.close();
            } catch (e) {
                /* already closed */
            }
            peer = null;
        }
        chunks = [];
        total = 0;
    }

    return {start, stop};
})();

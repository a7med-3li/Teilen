/*
 * Teilen Direct LAN P2P Engine
 * Multi-gigabyte disk-to-disk streaming over WebRTC DataChannel with zero server storage.
 * Secretstream XChaCha20-Poly1305 end-to-end encryption with backpressure control.
 */
window.TeilenLan = (() => {
    'use strict';

    const SIGN_CONTEXT = 'teilen-webrtc-v1';
    const LOCAL_TTL_SECONDS = 3600;
    const CHUNK_SIZE = 64 * 1024; // 64 KiB chunks
    const BUFFER_CAP = 4 * 1024 * 1024; // 4 MB backpressure pause
    const BUFFER_LOW = 512 * 1024; // 512 KB resume threshold

    let token = null;
    let currentSessionId = null;
    let onItem = null;
    let onTelemetry = null;
    let socket = null;
    let retryDelay = 1000;

    // Active incoming transfer state
    let rxPeer = null;
    let rxChannel = null;
    let rxOpfsWritable = null;
    let rxOpfsFileHandle = null;
    let rxChunks = [];
    let rxState = null; // Secretstream pull state
    let rxMetadata = null;
    let rxBytesTotal = 0;
    let rxBytesReceived = 0;
    let rxStartTime = 0;
    let rxFinished = false;

    // Active outgoing transfer state
    let activeSender = null;

    function signingMessage(type, sdp) {
        return sodium.from_string([SIGN_CONTEXT, type, sdp].join('|'));
    }

    async function start(rawToken, handleItem, handleTelemetry, sessionId) {
        token = rawToken || null;
        currentSessionId = sessionId || null;
        onItem = handleItem;
        onTelemetry = handleTelemetry;
        if (!token || !('RTCPeerConnection' in window)) {
            return;
        }
        await sodium.ready;
        connect();
    }

    function setSession(sessionId) {
        currentSessionId = sessionId || null;
        if (socket && socket.readyState === WebSocket.OPEN) {
            connect(); // Reconnect with new session
        }
    }

    function stop() {
        token = null;
        currentSessionId = null;
        onItem = null;
        onTelemetry = null;
        closeRxPeer();
        if (activeSender) {
            activeSender.cancel();
            activeSender = null;
        }
        const old = socket;
        socket = null;
        if (old) {
            try { old.close(); } catch (e) {}
        }
    }

    // ---------- signaling ----------

    function connect() {
        if (!token) return;
        const old = socket;
        socket = null;
        if (old) {
            try { old.close(); } catch (e) {}
        }

        const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
        let url = scheme + '://' + location.host + '/ws/signal?token=' + encodeURIComponent(token);
        if (currentSessionId) {
            url += '&sessionId=' + encodeURIComponent(currentSessionId);
        }

        const live = new WebSocket(url);
        socket = live;

        live.addEventListener('open', () => {
            retryDelay = 1000;
        });
        live.addEventListener('message', (message) => {
            handleSignal(message.data);
        });
        live.addEventListener('close', () => {
            if (socket !== live) return;
            socket = null;
            if (!token) return;
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

        if (activeSender && activeSender.handleSignal(message)) {
            return;
        }

        if (message.type === 'OFFER') {
            await acceptOffer(message);
        } else if (message.type === 'CANDIDATE' && rxPeer) {
            try {
                await rxPeer.addIceCandidate({
                    candidate: message.candidate,
                    sdpMid: message.sdpMid,
                    sdpMLineIndex: message.sdpMLineIndex
                });
            } catch (e) {}
        } else if (message.type === 'BYE') {
            closeRxPeer();
        }
    }

    // ---------- Receiver: Direct LAN WebRTC -> OPFS / Stream ----------

    async function acceptOffer(message) {
        if (!message.sdp || !message.signPk || !message.sig) return;

        const trusted = sodium.crypto_sign_verify_detached(
            TeilenCrypto.unb64(message.sig),
            signingMessage('OFFER', message.sdp),
            TeilenCrypto.unb64(message.signPk));
        if (!trusted) {
            console.warn('Refused WebRTC offer: invalid signature');
            return;
        }

        closeRxPeer();
        rxFinished = false;
        rxChunks = [];
        rxState = null;
        rxMetadata = null;
        rxBytesReceived = 0;
        rxBytesTotal = 0;
        rxStartTime = Date.now();

        rxPeer = new RTCPeerConnection({ iceServers: [] });

        rxPeer.addEventListener('icecandidate', (event) => {
            if (event.candidate && socket && socket.readyState === WebSocket.OPEN) {
                socket.send(JSON.stringify({
                    type: 'CANDIDATE',
                    candidate: event.candidate.candidate,
                    sdpMid: event.candidate.sdpMid,
                    sdpMLineIndex: event.candidate.sdpMLineIndex,
                    sessionId: currentSessionId
                }));
            }
        });

        rxPeer.addEventListener('datachannel', (event) => {
            rxChannel = event.channel;
            rxChannel.binaryType = 'arraybuffer';
            rxChannel.addEventListener('message', onRxMessage);
            rxChannel.addEventListener('close', finishRx);
        });

        rxPeer.addEventListener('connectionstatechange', () => {
            if (rxPeer && (rxPeer.connectionState === 'failed' || rxPeer.connectionState === 'disconnected')) {
                closeRxPeer();
            }
        });

        await rxPeer.setRemoteDescription({ type: 'offer', sdp: message.sdp });
        const answer = await rxPeer.createAnswer();
        await rxPeer.setLocalDescription(answer);

        const identity = await TeilenCrypto.ensureIdentity();
        const signature = sodium.crypto_sign_detached(
            signingMessage('ANSWER', answer.sdp), identity.privateKey);

        socket.send(JSON.stringify({
            type: 'ANSWER',
            sdp: answer.sdp,
            signPk: TeilenCrypto.b64(identity.publicKey),
            sig: TeilenCrypto.b64(signature),
            sessionId: currentSessionId
        }));
    }

    async function onRxMessage(event) {
        if (typeof event.data === 'string') {
            if (event.data.includes('eof')) {
                await finishRx();
            }
            return;
        }

        const chunk = new Uint8Array(event.data);
        rxBytesReceived += chunk.byteLength;

        // Telemetry calculation
        const elapsedSec = Math.max(0.1, (Date.now() - rxStartTime) / 1000);
        const speedMBps = ((rxBytesReceived / (1024 * 1024)) / elapsedSec).toFixed(1);
        const percent = rxBytesTotal > 0 ? Math.min(100, Math.round((rxBytesReceived / rxBytesTotal) * 100)) : 0;

        if (onTelemetry) {
            onTelemetry({
                direction: 'rx',
                fileName: rxMetadata ? rxMetadata.name : 'Incoming Direct LAN Stream',
                bytesReceived: rxBytesReceived,
                totalBytes: rxBytesTotal,
                percent,
                speedMBps
            });
        }

        // Initialize secretstream pull on first data chunk
        if (!rxState) {
            const HEADERBYTES = sodium.crypto_secretstream_xchacha20poly1305_HEADERBYTES;
            if (chunk.byteLength < HEADERBYTES + 4) {
                rxChunks.push(chunk);
                return;
            }

            const header = chunk.subarray(0, HEADERBYTES);
            const key = await TeilenCrypto.getSessionKey(currentSessionId);
            if (!key) {
                console.warn('No decryption key for incoming stream');
                closeRxPeer();
                return;
            }

            rxState = sodium.crypto_secretstream_xchacha20poly1305_init_pull(header, key);
            let offset = HEADERBYTES;

            // Read metadata length (4 bytes big-endian)
            const metaLen = (chunk[offset] << 24) | (chunk[offset + 1] << 16) | (chunk[offset + 2] << 8) | chunk[offset + 3];
            offset += 4;

            const metaCipher = chunk.subarray(offset, offset + metaLen);
            offset += metaLen;

            try {
                const metaPlain = sodium.crypto_secretstream_xchacha20poly1305_pull(rxState, metaCipher);
                rxMetadata = JSON.parse(sodium.to_string(metaPlain.message));
                rxBytesTotal = rxMetadata.size || 0;
            } catch (err) {
                console.warn('Failed to decrypt stream metadata', err);
                closeRxPeer();
                return;
            }

            // Setup OPFS writable if supported for 0-RAM streaming
            try {
                if (navigator.storage && navigator.storage.getDirectory) {
                    const root = await navigator.storage.getDirectory();
                    const safeName = 'teilen_dl_' + Date.now() + '_' + (rxMetadata.name || 'file');
                    rxOpfsFileHandle = await root.getFileHandle(safeName, { create: true });
                    rxOpfsWritable = await rxOpfsFileHandle.createWritable();
                }
            } catch (e) {
                rxOpfsWritable = null;
            }

            // Process any remaining payload in this first message
            if (offset < chunk.byteLength) {
                const payloadCipher = chunk.subarray(offset);
                await writePayloadChunk(payloadCipher);
            }
            return;
        }

        await writePayloadChunk(chunk);
    }

    async function writePayloadChunk(cipherChunk) {
        try {
            const pullRes = sodium.crypto_secretstream_xchacha20poly1305_pull(rxState, cipherChunk);
            if (rxOpfsWritable) {
                await rxOpfsWritable.write(pullRes.message);
            } else {
                rxChunks.push(pullRes.message);
            }
            if (pullRes.tag === sodium.crypto_secretstream_xchacha20poly1305_TAG_FINAL) {
                await finishRx();
            }
        } catch (e) {
            console.warn('Decryption error on chunk', e);
        }
    }

    async function finishRx() {
        if (rxFinished) return;
        rxFinished = true;

        let blob = null;
        const mime = (rxMetadata && rxMetadata.type) || 'application/octet-stream';
        const fileName = (rxMetadata && rxMetadata.name) || 'received-file';

        if (rxOpfsWritable) {
            try {
                await rxOpfsWritable.close();
                const file = await rxOpfsFileHandle.getFile();
                blob = file;
            } catch (e) {
                console.warn('OPFS finalize error', e);
            }
        }

        if (!blob) {
            blob = new Blob(rxChunks, { type: mime });
        }

        closeRxPeer();

        if (onTelemetry) {
            onTelemetry(null); // Clear telemetry UI
        }

        if (onItem && blob) {
            const now = Date.now();
            onItem({
                id: 'lan-' + now + '-' + Math.random().toString(36).slice(2, 8),
                sessionId: currentSessionId,
                type: typeOf(mime),
                content: fileName,
                mimeType: mime,
                sizeBytes: blob.size,
                createdAt: new Date(now).toISOString(),
                expiresAt: new Date(now + LOCAL_TTL_SECONDS * 1000).toISOString(),
                blobUrl: URL.createObjectURL(blob),
                isLan: true
            }, true);
        }
    }

    function closeRxPeer() {
        rxFinished = true;
        if (rxChannel) {
            try { rxChannel.close(); } catch (e) {}
            rxChannel = null;
        }
        if (rxPeer) {
            try { rxPeer.close(); } catch (e) {}
            rxPeer = null;
        }
        rxChunks = [];
        rxState = null;
        rxMetadata = null;
    }

    // ---------- Sender: Multi-GB Stream -> SecretStream -> WebRTC DataChannel ----------

    function sendFile(file, onProgress, onDone, onError) {
        if (activeSender) {
            activeSender.cancel();
        }

        let peer = null;
        let channel = null;
        let cancelled = false;
        let transferStarted = false;

        const startTime = Date.now();
        let bytesSent = 0;
        const totalBytes = file.size;

        function cancel() {
            cancelled = true;
            if (channel) {
                try { channel.close(); } catch (e) {}
            }
            if (peer) {
                try { peer.close(); } catch (e) {}
            }
            if (onError) onError('Transfer cancelled');
        }

        peer = new RTCPeerConnection({ iceServers: [] });

        peer.addEventListener('icecandidate', (event) => {
            if (event.candidate && socket && socket.readyState === WebSocket.OPEN) {
                socket.send(JSON.stringify({
                    type: 'CANDIDATE',
                    candidate: event.candidate.candidate,
                    sdpMid: event.candidate.sdpMid,
                    sdpMLineIndex: event.candidate.sdpMLineIndex,
                    sessionId: currentSessionId
                }));
            }
        });

        channel = peer.createDataChannel('teilen-transfer', { ordered: true });
        channel.binaryType = 'arraybuffer';
        channel.bufferedAmountLowThreshold = BUFFER_LOW;

        channel.addEventListener('open', () => {
            transferStarted = true;
            streamFileToChannel();
        });

        peer.createOffer().then(async (offer) => {
            await peer.setLocalDescription(offer);
            const identity = await TeilenCrypto.ensureIdentity();
            const signature = sodium.crypto_sign_detached(
                signingMessage('OFFER', offer.sdp), identity.privateKey);

            socket.send(JSON.stringify({
                type: 'OFFER',
                sdp: offer.sdp,
                signPk: TeilenCrypto.b64(identity.publicKey),
                sig: TeilenCrypto.b64(signature),
                sessionId: currentSessionId
            }));
        }).catch((err) => {
            if (onError) onError('Could not initiate WebRTC: ' + err.message);
        });

        function handleSignal(msg) {
            if (msg.type === 'ANSWER') {
                peer.setRemoteDescription(new RTCSessionDescription({ type: 'answer', sdp: msg.sdp })).catch(() => {});
                return true;
            }
            if (msg.type === 'CANDIDATE') {
                peer.addIceCandidate(new RTCIceCandidate({
                    candidate: msg.candidate,
                    sdpMid: msg.sdpMid,
                    sdpMLineIndex: msg.sdpMLineIndex
                })).catch(() => {});
                return true;
            }
            return false;
        }

        async function streamFileToChannel() {
            try {
                const key = await TeilenCrypto.getSessionKey(currentSessionId);
                const state = sodium.crypto_secretstream_xchacha20poly1305_init_push(key);
                const header = state.header;

                // Send header + metadata
                const metaObj = { name: file.name, type: file.type, size: file.size };
                const metaBytes = sodium.from_string(JSON.stringify(metaObj));
                const metaCipher = sodium.crypto_secretstream_xchacha20poly1305_push(
                    state.state, metaBytes, null, sodium.crypto_secretstream_xchacha20poly1305_TAG_MESSAGE
                );

                const metaLen = metaCipher.length;
                const headerMsg = new Uint8Array(header.length + 4 + metaLen);
                headerMsg.set(header, 0);
                headerMsg[header.length] = (metaLen >> 24) & 0xff;
                headerMsg[header.length + 1] = (metaLen >> 16) & 0xff;
                headerMsg[header.length + 2] = (metaLen >> 8) & 0xff;
                headerMsg[header.length + 3] = metaLen & 0xff;
                headerMsg.set(metaCipher, header.length + 4);

                channel.send(headerMsg);

                // Stream file in 64 KiB chunks with backpressure
                let offset = 0;
                while (offset < totalBytes) {
                    if (cancelled) return;

                    // Backpressure control: wait if buffer exceeds cap
                    if (channel.bufferedAmount > BUFFER_CAP) {
                        await new Promise((res) => {
                            const onLow = () => {
                                channel.removeEventListener('bufferedamountlow', onLow);
                                res();
                            };
                            channel.addEventListener('bufferedamountlow', onLow);
                            setTimeout(res, 200); // Safety timeout
                        });
                    }

                    const end = Math.min(offset + CHUNK_SIZE, totalBytes);
                    const slice = file.slice(offset, end);
                    const chunkBuffer = await slice.arrayBuffer();
                    const plainChunk = new Uint8Array(chunkBuffer);
                    const isFinal = (end >= totalBytes);

                    const tag = isFinal
                        ? sodium.crypto_secretstream_xchacha20poly1305_TAG_FINAL
                        : sodium.crypto_secretstream_xchacha20poly1305_TAG_MESSAGE;

                    const cipherChunk = sodium.crypto_secretstream_xchacha20poly1305_push(
                        state.state, plainChunk, null, tag
                    );

                    channel.send(cipherChunk);
                    offset = end;
                    bytesSent = offset;

                    const elapsedSec = Math.max(0.1, (Date.now() - startTime) / 1000);
                    const speedMBps = ((bytesSent / (1024 * 1024)) / elapsedSec).toFixed(1);
                    const percent = Math.min(100, Math.round((bytesSent / totalBytes) * 100));

                    if (onProgress) {
                        onProgress({ bytesSent, totalBytes, percent, speedMBps });
                    }
                }

                // Signal EOF
                channel.send(JSON.stringify({ type: 'eof' }));
                setTimeout(() => {
                    if (onDone) onDone();
                    cancel();
                }, 1000);
            } catch (err) {
                if (onError) onError('Streaming error: ' + err.message);
                cancel();
            }
        }

        activeSender = { cancel, handleSignal };
        return activeSender;
    }

    function typeOf(mime) {
        if (mime.startsWith('image/')) return 'IMAGE';
        if (mime === 'application/pdf') return 'PDF';
        if (mime.startsWith('audio/')) return 'AUDIO';
        return 'FILE';
    }

    return {
        start,
        setSession,
        stop,
        sendFile
    };
})();

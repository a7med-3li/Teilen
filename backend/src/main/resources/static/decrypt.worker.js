/*
 * Decrypts a Teilen envelope off the main thread.
 *
 * The envelope is exactly what the phone's CipherStreamEngine writes:
 *
 *   [24-byte secretstream header]
 *   [4-byte big-endian length of the metadata chunk]
 *   [metadata chunk  = plaintext metadata + 17-byte overhead]
 *   [payload chunk] [payload chunk] … [final payload chunk]
 *
 * Payload chunks are 64 KiB of plaintext plus the 17-byte secretstream overhead, so 65553 bytes on
 * the wire; only the last one is shorter (or empty). The final chunk carries TAG_FINAL.
 *
 * Messages in:  { id, masterKey: Uint8Array, encrypted: ArrayBuffer }
 * Messages out: { id, metadata: object, blob: Blob }  or  { id, error: string }
 */
'use strict';

importScripts('vendor/libsodium-sumo.js', 'vendor/libsodium-wrappers.js');

const HEADER_BYTES = 24;
const TAG_BYTES = 17;
const PLAIN_CHUNK = 64 * 1024;
const WIRE_CHUNK = PLAIN_CHUNK + TAG_BYTES;

self.onmessage = async (event) => {
    const {id, masterKey, encrypted} = event.data;
    try {
        await sodium.ready;
        const result = decrypt(new Uint8Array(masterKey), new Uint8Array(encrypted));
        const blob = new Blob(result.chunks, {type: result.metadata.type || 'application/octet-stream'});
        self.postMessage({id, metadata: result.metadata, blob});
    } catch (e) {
        self.postMessage({id, error: (e && e.message) || String(e)});
    }
};

function decrypt(masterKey, bytes) {
    if (bytes.length < HEADER_BYTES + 4) {
        throw new Error('envelope is too short');
    }

    const header = bytes.subarray(0, HEADER_BYTES);
    const metaLength = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
        .getUint32(HEADER_BYTES, false);

    let offset = HEADER_BYTES + 4;
    if (metaLength < TAG_BYTES || offset + metaLength > bytes.length) {
        throw new Error('envelope metadata is malformed');
    }

    const state = sodium.crypto_secretstream_xchacha20poly1305_init_pull(header, masterKey);

    const metaChunk = bytes.subarray(offset, offset + metaLength);
    offset += metaLength;
    const meta = sodium.crypto_secretstream_xchacha20poly1305_pull(state, metaChunk);
    if (!meta) {
        throw new Error('wrong account key');
    }
    const metadata = JSON.parse(sodium.to_string(meta.message));

    // every chunk but the last is exactly WIRE_CHUNK, so the last one is simply whatever is left
    const chunks = [];
    let finished = false;
    while (offset < bytes.length) {
        const length = Math.min(WIRE_CHUNK, bytes.length - offset);
        const pulled = sodium.crypto_secretstream_xchacha20poly1305_pull(
            state, bytes.subarray(offset, offset + length));
        if (!pulled) {
            throw new Error('envelope is corrupt');
        }
        offset += length;
        chunks.push(pulled.message);
        if (pulled.tag === sodium.crypto_secretstream_xchacha20poly1305_TAG_FINAL) {
            finished = true;
            break;
        }
    }

    if (!finished) {
        throw new Error('envelope is missing its final chunk');
    }
    return {metadata, chunks};
}

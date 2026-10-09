package com.teilen.app

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.SecretStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Seals and opens a file's bytes as one streaming envelope.
 *
 * The envelope is what the browser's `decrypt.worker.js` expects, and it is the whole format:
 *
 *   [24-byte secretstream header]
 *   [4-byte big-endian length of the metadata chunk]
 *   [metadata chunk  = plaintext metadata + 17-byte overhead]
 *   [payload chunk] [payload chunk] … [final payload chunk]
 *
 * Every payload chunk is 64 KiB of plaintext plus the 17-byte secretstream overhead, so 65553 bytes
 * on the wire; the last one may be shorter, and is the one that carries TAG_FINAL. An empty file
 * still gets one (empty) final chunk, so the receiver always has something to authenticate.
 *
 * The caller decides what the metadata is; today it is the file's name, type and size.
 */
object CipherStreamEngine {

    private const val CHUNK = 64 * 1024
    private const val ABYTES = SecretStream.ABYTES
    private const val HEADERBYTES = SecretStream.HEADERBYTES
    private const val WIRE_CHUNK = CHUNK + ABYTES

    private val sodium = LazySodiumAndroid(SodiumAndroid())

    /**
     * Encrypts [input] into the envelope on [output]. The key is the account's master key; nothing
     * here touches the network.
     */
    fun encrypt(key: ByteArray, metadata: ByteArray, input: InputStream, output: OutputStream) {
        val state = SecretStream.State.ByReference()
        val header = ByteArray(HEADERBYTES)
        sodium.cryptoSecretStreamInitPush(state, header, key)
        output.write(header)

        val metaCipher = ByteArray(metadata.size + ABYTES)
        sodium.cryptoSecretStreamPush(
            state, metaCipher, metadata, metadata.size.toLong(), SecretStream.TAG_MESSAGE
        )
        output.write(intToStringLength(metaCipher.size))
        output.write(metaCipher)

        val buffer = ByteArray(CHUNK)
        var current = readFully(input, buffer)
        if (current == 0) {
            // an empty file is one final, empty chunk
            val finalCipher = ByteArray(ABYTES)
            sodium.cryptoSecretStreamPush(
                state, finalCipher, ByteArray(0), 0, SecretStream.TAG_FINAL
            )
            output.write(finalCipher)
            return
        }

        // read one chunk ahead so the last one is known to be last without relying on available()
        val next = ByteArray(CHUNK)
        var nextLength = readFully(input, next)
        while (true) {
            val isFinal = nextLength == 0
            val cipher = ByteArray(current + ABYTES)
            sodium.cryptoSecretStreamPush(
                state, cipher, buffer, current.toLong(),
                if (isFinal) SecretStream.TAG_FINAL else SecretStream.TAG_MESSAGE
            )
            output.write(cipher)
            if (isFinal) {
                return
            }
            System.arraycopy(next, 0, buffer, 0, nextLength)
            current = nextLength
            nextLength = readFully(input, next)
        }
    }

    /**
     * Decrypts an envelope from [input] onto [output].
     *
     * @return the metadata the sender sealed alongside the bytes
     * @throws IOException when the envelope is truncated or does not authenticate
     */
    fun decrypt(key: ByteArray, input: InputStream, output: OutputStream): ByteArray {
        val header = readExactly(input, HEADERBYTES)
            ?: throw IOException("envelope is too short to hold a header")

        val state = SecretStream.State.ByReference()
        sodium.cryptoSecretStreamInitPull(state, header, key)

        val lengthBytes = readExactly(input, 4)
            ?: throw IOException("envelope is missing its metadata length")
        val metaLength = ((lengthBytes[0].toInt() and 0xff) shl 24) or
            ((lengthBytes[1].toInt() and 0xff) shl 16) or
            ((lengthBytes[2].toInt() and 0xff) shl 8) or
            (lengthBytes[3].toInt() and 0xff)
        if (metaLength < ABYTES) {
            throw IOException("envelope metadata is malformed")
        }
        val metaCipher = readExactly(input, metaLength)
            ?: throw IOException("envelope metadata is truncated")
        val metadata = ByteArray(metaLength - ABYTES)
        if (!sodium.cryptoSecretStreamPull(state, metadata, ByteArray(1), metaCipher, metaLength.toLong())) {
            throw IOException("wrong account key")
        }

        val buffer = ByteArray(WIRE_CHUNK)
        val tag = ByteArray(1)
        while (true) {
            val filled = readFully(input, buffer)
            if (filled < ABYTES) {
                throw IOException("envelope is missing its final chunk")
            }
            val message = ByteArray(filled - ABYTES)
            if (!sodium.cryptoSecretStreamPull(state, message, tag, buffer, filled.toLong())) {
                throw IOException("envelope is corrupt")
            }
            output.write(message)
            if (tag[0] == SecretStream.TAG_FINAL) {
                return metadata
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun intToStringLength(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte()
    )

    /** fills [buffer] as far as the stream allows; returns how many bytes were read, 0 at once */
    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read == -1) {
                break
            }
            offset += read
        }
        return offset
    }

    /** reads exactly [count] bytes, or null if the stream ends first */
    private fun readExactly(input: InputStream, count: Int): ByteArray? {
        val out = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(out, offset, count - offset)
            if (read == -1) {
                return null
            }
            offset += read
        }
        return out
    }
}

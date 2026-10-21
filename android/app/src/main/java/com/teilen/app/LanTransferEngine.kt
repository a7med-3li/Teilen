package com.teilen.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.io.OutputStream
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The direct LAN fast-path: try to hand one file straight to the account's browser over a WebRTC
 * data channel, and give up after a strict 2.5 s so the caller can fall back to the ordinary HTTP
 * upload. Nothing here is trusted: the SDP is signed with this device's Ed25519 identity, and the
 * WebRTC layer still encrypts DTLS on top of the account-key envelope we push through it.
 *
 * <p>One file per session, deliberately: the receiver buffers exactly one secretstream envelope and
 * opens it with the account key. A batch share simply runs this per file, and each file that misses
 * the window goes the long way round.
 */
class LanTransferEngine(private val context: Context) {

    /** one file, opened lazily so an idle attempt costs nothing */
    class Payload(
        val name: String,
        val mime: String,
        val size: Long,
        val open: () -> java.io.InputStream
    )

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().build()
    private val done = AtomicBoolean(false)

    private var signaling: WebSocket? = null
    private var peer: PeerConnection? = null
    private var channel: DataChannel? = null
    private var payload: Payload? = null
    private var onProgressCallback: ((Long, Long) -> Unit)? = null
    private var onResult: ((Boolean) -> Unit)? = null

    private val timeout = Runnable { finish(false) }

    /**
     * Tries the local path once. [onResult] is called exactly once, on the main thread: true when
     * the envelope made it over the data channel, false when the caller should use the fallback.
     */
    fun attempt(
        payload: Payload,
        onProgress: ((Long, Long) -> Unit)? = null,
        onResult: (Boolean) -> Unit
    ) {
        this.payload = payload
        this.onProgressCallback = onProgress
        this.onResult = onResult
        main.postDelayed(timeout, FALLBACK_MS)

        try {
            val request = Request.Builder().url(signalUrl()).build()
            signaling = http.newWebSocket(request, object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) = handleSignal(text)
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    finish(false)

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    // the relay dropping us before the channel opened is a miss, not a crash
                    if (channel?.state() != DataChannel.State.OPEN) {
                        finish(false)
                    }
                }
            })
            openPeer()
        } catch (e: Exception) {
            finish(false)
        }
    }

    // ------------------------------------------------------------------ WebRTC

    private fun openPeer() {
        val connection = factory(context).createPeerConnection(rtcConfig(), observer) ?: run {
            finish(false)
            return
        }
        peer = connection
        val channel = connection.createDataChannel(
            CHANNEL_LABEL,
            DataChannel.Init().apply { ordered = true }
        )
        this.channel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onMessage(buffer: DataChannel.Buffer) = Unit

            override fun onStateChange() {
                if (channel.state() == DataChannel.State.OPEN) {
                    // the data channel opening is what makes the attempt worthwhile
                    main.removeCallbacks(timeout)
                    Thread { pump(channel) }.apply { name = "teilen-lan-send" }.start()
                }
            }
        })

        connection.createOffer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) {
                connection.setLocalDescription(NoopSdp, description)
                signal("OFFER", description.description, sign = true)
            }

            override fun onCreateFailure(error: String?) = finish(false)
            override fun onSetSuccess() = Unit
            override fun onSetFailure(error: String?) = finish(false)
        }, MediaConstraints())
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            signal(
                "CANDIDATE", candidate.sdp, sign = false,
                extra = mapOf("sdpMid" to candidate.sdpMid, "sdpMLineIndex" to candidate.sdpMLineIndex)
            )
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            if (state == PeerConnection.IceConnectionState.FAILED ||
                state == PeerConnection.IceConnectionState.CLOSED
            ) {
                if (channel?.state() != DataChannel.State.OPEN) {
                    finish(false)
                }
            }
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
        override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) {
            setupReceiver(channel)
        }
        override fun onRenegotiationNeeded() = Unit
    }

    private fun setupReceiver(channel: DataChannel) {
        val pipedOut = java.io.PipedOutputStream()
        val pipedIn = java.io.PipedInputStream(pipedOut, 256 * 1024)
        val key = SecureKeyManager.ensureSessionKey(context, Session.activeSessionId(context))

        Thread {
            try {
                val tempFile = java.io.File(context.cacheDir, "teilen_lan_${System.currentTimeMillis()}.tmp")
                tempFile.outputStream().use { fileOut ->
                    val metaBytes = CipherStreamEngine.decrypt(key, pipedIn, fileOut)
                    val metaJson = JSONObject(String(metaBytes, Charsets.UTF_8))
                    val fileName = metaJson.optString("name").ifBlank { "received_lan_file" }
                    val mimeType = metaJson.optString("type").ifBlank { "*/*" }
                    saveToDownloads(tempFile, fileName, mimeType)
                    tempFile.delete()
                }
            } catch (e: Exception) {
                // stream finished or error
            } finally {
                runCatching { pipedIn.close() }
            }
        }.apply { name = "teilen-lan-receive" }.start()

        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() = Unit
            override fun onMessage(buffer: DataChannel.Buffer) {
                val data = buffer.data
                val bytes = ByteArray(data.remaining())
                data.get(bytes)
                if (String(bytes, Charsets.UTF_8).contains("\"type\":\"eof\"")) {
                    runCatching { pipedOut.close() }
                } else {
                    runCatching { pipedOut.write(bytes) }
                }
            }
        })
    }

    private fun saveToDownloads(sourceFile: java.io.File, fileName: String, mimeType: String) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/Teilen")
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val insertedUri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (insertedUri != null) {
                resolver.openOutputStream(insertedUri)?.use { out ->
                    sourceFile.inputStream().use { it.copyTo(out) }
                }
                contentValues.clear()
                contentValues.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(insertedUri, contentValues, null, null)
            }
        } else {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val targetDir = java.io.File(downloadsDir, "Teilen").apply { mkdirs() }
            val targetFile = java.io.File(targetDir, fileName)
            sourceFile.inputStream().use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    /** the envelope goes out here: encrypt to a throttled data-channel sink, then signal eof */
    private fun pump(channel: DataChannel) {
        try {
            val file = payload ?: throw IllegalStateException("no payload")
            val key = SecureKeyManager.ensureSessionKey(context, Session.activeSessionId(context))
            val metadata = JSONObject()
                .put("name", file.name)
                .put("type", file.mime)
                .put("size", file.size)
                .toString()
                .toByteArray(Charsets.UTF_8)

            val sink = ChannelSink(channel, file.size)
            file.open().use { input -> CipherStreamEngine.encrypt(key, metadata, input, sink) }
            sink.flush()
            channel.send(DataChannel.Buffer(ByteBuffer.wrap(EOF.toByteArray(Charsets.UTF_8)), false))
            finish(true)
        } catch (e: Exception) {
            finish(false)
        }
    }

    // ------------------------------------------------------------------ signaling

    private fun handleSignal(text: String) {
        val message = try {
            JSONObject(text)
        } catch (e: Exception) {
            return
        }
        when (message.optString("type")) {
            "ANSWER" -> {
                val sdp = message.optString("sdp").ifBlank { return }
                if (!verify(message)) {
                    finish(false)
                    return
                }
                main.removeCallbacks(timeout)
                peer?.setRemoteDescription(NoopSdp, SessionDescription(SessionDescription.Type.ANSWER, sdp))
            }

            "CANDIDATE" -> {
                val sdp = message.optString("candidate").ifBlank { return }
                peer?.addIceCandidate(
                    IceCandidate(
                        message.optString("sdpMid").ifBlank { "0" },
                        message.optInt("sdpMLineIndex", 0),
                        sdp
                    )
                )
            }

            "BYE" -> if (channel?.state() != DataChannel.State.OPEN) {
                finish(false)
            }
        }
    }

    private fun signal(
        type: String,
        value: String,
        sign: Boolean,
        extra: Map<String, Any?> = emptyMap()
    ) {
        val identity = SecureKeyManager.ensureIdentity(context)
        val json = JSONObject()
            .put("type", type)
        json.put(if (type == "CANDIDATE") "candidate" else "sdp", value)
        extra.forEach { (key, v) -> if (v != null) json.put(key, v) }
        if (sign) {
            json.put("signPk", b64(identity.publicKey))
            json.put("sig", b64(PairingCrypto.signDetached(signingMessage(type, value), identity.secretKey)))
        }
        signaling?.send(json.toString())
    }

    private fun verify(message: JSONObject): Boolean {
        val sdp = message.optString("sdp")
        val signPk = message.optString("signPk").ifBlank { return false }
        val sig = message.optString("sig").ifBlank { return false }
        return PairingCrypto.verifyDetached(
            unb64(sig),
            signingMessage("ANSWER", sdp),
            unb64(signPk)
        )
    }

    private fun signingMessage(type: String, sdp: String): ByteArray =
        "$SIGN_CONTEXT|$type|$sdp".toByteArray(Charsets.UTF_8)

    // ------------------------------------------------------------------ lifecycle

    private fun finish(ok: Boolean) {
        if (!done.compareAndSet(false, true)) {
            return
        }
        main.removeCallbacks(timeout)
        runCatching { channel?.close() }
        runCatching { channel?.unregisterObserver() }
        runCatching { peer?.close() }
        runCatching { signaling?.close(1000, "done") }
        peer = null
        channel = null
        signaling = null
        val callback = onResult
        onResult = null
        main.post { callback?.invoke(ok) }
    }

    /** sends whole 64 KiB messages and blocks until the channel's buffer drains below the cap */
    private inner class ChannelSink(private val channel: DataChannel, private val totalBytes: Long) : OutputStream() {
        private val buffer = ByteArray(64 * 1024)
        private var count = 0
        private var totalSent = 0L

        override fun write(b: Int) {
            buffer[count++] = b.toByte()
            if (count == buffer.size) {
                flushChunk()
            }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var offset = off
            var remaining = len
            while (remaining > 0) {
                val take = minOf(buffer.size - count, remaining)
                System.arraycopy(b, offset, buffer, count, take)
                count += take
                offset += take
                remaining -= take
                if (count == buffer.size) {
                    flushChunk()
                }
            }
        }

        override fun flush() {
            if (count > 0) {
                flushChunk()
            }
        }

        private fun flushChunk() {
            while (channel.bufferedAmount() > BUFFER_CAP) {
                Thread.sleep(10)
            }
            val message = buffer.copyOf(count)
            if (!channel.send(DataChannel.Buffer(ByteBuffer.wrap(message), true))) {
                throw IllegalStateException("the data channel refused a chunk")
            }
            totalSent += count
            count = 0
            val cb = onProgressCallback
            if (cb != null) {
                main.post { cb(totalSent, totalBytes) }
            }
        }
    }

    private object NoopSdp : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }

    private fun signalUrl(): String {
        val base = Server.normalize(Server.get(context))
        val ws = base.replaceFirst("http", "ws")
        val token = URLEncoder.encode(Session.token(context).orEmpty(), "UTF-8")
        val activeSession = Session.activeSessionId(context)
        val sessionParam = if (!activeSession.isNullOrBlank()) "&sessionId=" + URLEncoder.encode(activeSession, "UTF-8") else ""
        return "$ws/ws/signal?token=$token$sessionParam"
    }

    private fun rtcConfig(): PeerConnection.RTCConfiguration =
        PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun unb64(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

    companion object {
        private const val CHANNEL_LABEL = "teilen-transfer"
        private const val SIGN_CONTEXT = "teilen-webrtc-v1"
        private const val FALLBACK_MS = 2500L
        private const val BUFFER_CAP = 4L * 1024 * 1024
        private const val EOF = "{\"type\":\"eof\"}"

        @Volatile
        private var factory: PeerConnectionFactory? = null

        /** the factory is process-wide and expensive; made once, lazily */
        @Synchronized
        private fun factory(context: Context): PeerConnectionFactory {
            factory?.let { return it }
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .createInitializationOptions()
            )
            val created = PeerConnectionFactory.builder().createPeerConnectionFactory()
            factory = created
            return created
        }
    }
}

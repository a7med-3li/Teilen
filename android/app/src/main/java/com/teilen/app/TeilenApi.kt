package com.teilen.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The entire backend client: pairing, text POSTs and multipart uploads, no HTTP library.
 *
 * Every call takes the bearer token explicitly, because the caller is the only thing that knows
 * which account it is acting for. A null token means "not paired", and the server will say so.
 */
object TeilenApi {

    /** server-side cap; checked here too so an oversized file never leaves the phone */
    const val MAX_UPLOAD_BYTES = 30L * 1024 * 1024

    /**
     * What a 401 comes back as, and the one string callers compare against: it means the token is
     * no longer paired with anything, which is the app's cue to forget it and show the account card
     * again rather than to sit there looking broken.
     */
    const val UNPAIRED_MESSAGE = "not paired any more — pair this phone again in the app"

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val BUFFER = 64 * 1024

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    // ------------------------------------------------------------------ accounts

    /** a credential came back, and with it who it belongs to */
    data class SignedIn(
        val token: String,
        val phone: String?,
        val deviceName: String?,
        val deviceId: String?
    )

    /** a code to show, and the secret to collect its token with */
    data class PairingOffer(
        val deviceCode: String,
        val userCode: String,
        val pollIntervalMillis: Long
    )

    /** one poll of a pairing this device is waiting on */
    sealed class PairingPoll {
        /** nobody has answered yet: not an error, so not an error string */
        data object Waiting : PairingPoll()

        /** @param keyPackage the approver's sealed account-key bundle, or null if none was left */
        data class Paired(val signedIn: SignedIn, val keyPackage: String?) : PairingPoll()

        /** denied, expired, or never issued — the message says which */
        data class Failed(val message: String) : PairingPoll()
    }

    /**
     * What a scanned code is asking for, so the prompt can name it before anyone taps allow.
     *
     * @param publicKey the newcomer's single-use X25519 key, to seal the account key to, or null
     */
    data class PairingPreview(
        val deviceName: String,
        val deviceType: String,
        val platform: String?,
        val publicKey: String?
    )

    /**
     * Claims a phone number and pairs this device with it.
     *
     * This is the one call that hands out a credential to a stranger, and it proves nothing about
     * the number: whoever asks first owns it. Adding an OTP would mean checking it right here.
     *
     * @param onResult always on the main thread; [CreateResult.Taken] is the caller's cue to switch
     *                to pairing instead of typing a different number
     */
    fun createAccount(
        baseUrl: String,
        phone: String,
        displayName: String,
        deviceName: String,
        onResult: (CreateResult) -> Unit
    ) {
        Thread {
            val result = try {
                val reply = postBlocking(
                    baseUrl,
                    "/api/auth/account",
                    token = null,
                    JSONObject()
                        .put("phone", phone)
                        .put("displayName", displayName)
                        .put("deviceName", deviceName)
                )
                when {
                    reply.ok -> CreateResult.Created(signedInFrom(reply))
                    reply.status == 409 -> CreateResult.Taken(reply.message)
                    else -> CreateResult.Failed(reply.message)
                }
            } catch (e: IOException) {
                CreateResult.Failed(unreachable(baseUrl, e))
            } catch (e: Exception) {
                CreateResult.Failed(e.message ?: e.javaClass.simpleName)
            }
            main.post { onResult(result) }
        }.apply { name = "teilen-auth" }.start()
    }

    /** what claiming a number came to */
    sealed class CreateResult {
        data class Created(val signedIn: SignedIn) : CreateResult()

        /** the number already has an account, so this device has to be paired from another one */
        data class Taken(val message: String) : CreateResult()

        data class Failed(val message: String) : CreateResult()
    }

    /**
     * Step one of pairing from this phone: ask for a code, then keep polling for a token.
     *
     * @param publicKey this device's single-use X25519 public key, base64, so the approving device
     *                  can seal the account key to it; null to pair without a key exchange
     * @param onOffer the code to show, always on the main thread
     * @param onPolled the outcome of every poll, always on the main thread
     */
    fun pairThisDevice(
        baseUrl: String,
        deviceName: String,
        publicKey: String?,
        onOffer: (PairingOffer?) -> Unit,
        onPolled: (PairingPoll) -> Unit
    ) {
        Thread {
            val offer = try {
                val body = JSONObject()
                    .put("deviceName", deviceName)
                    .put("deviceType", "PHONE")
                    .put("platform", "Android ${android.os.Build.VERSION.RELEASE}")
                if (publicKey != null) {
                    body.put("publicKey", publicKey)
                }
                val reply = postBlocking(baseUrl, "/api/auth/device/code", token = null, body)
                if (!reply.ok) {
                    null
                } else {
                    PairingOffer(
                        deviceCode = reply.str("deviceCode").orEmpty(),
                        userCode = reply.str("userCode").orEmpty(),
                        pollIntervalMillis = reply.num("pollIntervalSeconds", 3L) * 1000L
                    )
                }
            } catch (e: Exception) {
                null
            }
            main.post {
                onOffer(offer)
                if (offer != null && offer.deviceCode.isNotBlank()) {
                    pollForToken(baseUrl, offer, onPolled)
                }
            }
        }.apply { name = "teilen-pair" }.start()
    }

    /** step three of pairing, once per poll interval until it settles */
    private fun pollForToken(baseUrl: String, offer: PairingOffer, onPolled: (PairingPoll) -> Unit) {
        Thread {
            val poll = try {
                val reply = postBlocking(
                    baseUrl,
                    "/api/auth/device/token",
                    token = null,
                    JSONObject().put("deviceCode", offer.deviceCode)
                )
                when {
                    reply.status == 202 -> PairingPoll.Waiting
                    reply.ok -> PairingPoll.Paired(signedInFrom(reply), reply.str("keyPackage"))
                    else -> PairingPoll.Failed(reply.message)
                }
            } catch (e: IOException) {
                PairingPoll.Failed(unreachable(baseUrl, e))
            } catch (e: Exception) {
                PairingPoll.Failed(e.message ?: e.javaClass.simpleName)
            }
            main.post {
                onPolled(poll)
                // 202 means keep waiting; anything else has settled and the caller stops the loop
                if (poll is PairingPoll.Waiting) {
                    main.postDelayed({ pollForToken(baseUrl, offer, onPolled) }, offer.pollIntervalMillis)
                }
            }
        }.apply { name = "teilen-pair" }.start()
    }

    /**
     * What a code in a deep link is asking for, so the dialog can name the newcomer before anyone
     * taps allow. Open on purpose — the code *is* the credential, so holding it is as good as being
     * that device — and it only ever answers with a name and a kind.
     *
     * @param onResult null when the code is unknown, already spent, or ran out
     */
    fun describePairing(baseUrl: String, userCode: String, onResult: (PairingPreview?) -> Unit) {
        Thread {
            val preview = try {
                val reply = getBlocking(
                    baseUrl,
                    "/api/auth/device/pending?code=" + URLEncoder.encode(userCode, "UTF-8")
                )
                if (reply.ok) {
                    PairingPreview(
                        deviceName = reply.str("deviceName") ?: "a device",
                        deviceType = reply.str("deviceType") ?: "WEB",
                        platform = reply.str("platform"),
                        publicKey = reply.str("publicKey")
                    )
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
            main.post { onResult(preview) }
        }.apply { name = "teilen-auth" }.start()
    }

    /**
     * Step two, from a device that is already paired: allow or turn down a code someone showed.
     *
     * @param keyPackage on approve, the account key sealed for the newcomer's offered public key;
     *                   null when there was no key to exchange and pairing proceeds without one
     * @param onResult (ok, message), always on the main thread
     */
    fun decidePairing(
        baseUrl: String,
        token: String,
        userCode: String,
        allow: Boolean,
        keyPackage: String?,
        onResult: (ok: Boolean, message: String) -> Unit
    ) {
        Thread {
            val result = try {
                val body = JSONObject().put("userCode", userCode)
                if (allow && keyPackage != null) {
                    body.put("keyPackage", keyPackage)
                }
                val reply = postBlocking(
                    baseUrl,
                    "/api/auth/device/" + if (allow) "approve" else "deny",
                    token,
                    body
                )
                if (reply.ok) {
                    true to (reply.str("deviceName") ?: if (allow) "paired" else "turned down")
                } else {
                    false to reply.message
                }
            } catch (e: IOException) {
                false to unreachable(baseUrl, e)
            } catch (e: Exception) {
                false to (e.message ?: e.javaClass.simpleName)
            }
            main.post { onResult(result.first, result.second) }
        }.apply { name = "teilen-auth" }.start()
    }

    // ------------------------------------------------------------------ items

    /**
     * The whole live feed for the token's account, newest first. One request, no cursor: the list
     * is bounded by the time-to-live, so re-reading it costs less than keeping a delta in step.
     *
     * @param onResult (items, error), always on the main thread; exactly one of the two is set
     */
    fun fetchItems(baseUrl: String, token: String?, onResult: (List<FeedItem>?, String?) -> Unit) {
        Thread {
            val result = try {
                val connection = open(baseUrl, "/api/items", token, "GET")
                val (status, text) = try {
                    readBody(connection)
                } finally {
                    connection.disconnect()
                }
                when {
                    status in 200..299 -> parseItems(text) to null
                    status == 401 -> null to UNPAIRED_MESSAGE
                    else -> null to "server said $status"
                }
            } catch (e: IOException) {
                null to unreachable(baseUrl, e)
            } catch (e: Exception) {
                null to (e.message ?: e.javaClass.simpleName)
            }
            val items = result.first
            val error = result.second
            main.post { onResult(items, error) }
        }.apply { name = "teilen-feed" }.start()
    }

    /** the feed is a bare array, so it cannot go through [Reply]'s object parser */
    private fun parseItems(text: String): List<FeedItem> {
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { FeedItem.parse(it) }
        }
    }

    /** @param onResult (ok, message), always on the main thread */
    fun send(
        baseUrl: String,
        token: String?,
        content: String,
        ttlSeconds: Long,
        onResult: (ok: Boolean, message: String) -> Unit
    ) {
        Thread {
            val result = try {
                val url = URL("${Server.normalize(baseUrl)}/api/items")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    authorize(token)
                }
                try {
                    val body = JSONObject()
                        .put("content", content)
                        .put("ttlSeconds", ttlSeconds)
                        .toString()
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    finish(connection, "sent")
                } finally {
                    connection.disconnect()
                }
            } catch (e: IOException) {
                false to unreachable(baseUrl, e)
            } catch (e: Exception) {
                false to (e.message ?: e.javaClass.simpleName)
            }
            main.post { onResult(result.first, result.second) }
        }.apply { name = "teilen-upload" }.start()
    }

    /** how a batch of uploads ended */
    data class BatchOutcome(val sent: Int, val total: Int, val lastError: String?) {
        val failed: Int get() = total - sent
    }

    /**
     * One file per request, so a multi-select share becomes several feed items and each keeps
     * its own expiry. Runs the list in order on one worker at a time — parallel uploads over a
     * phone connection just make everything slower.
     *
     * @param onProgress index (1-based), file count, bytes written, total bytes if known
     * @param onDone always on the main thread, once, after the last file
     */
    fun uploadAll(
        context: Context,
        baseUrl: String,
        token: String?,
        uris: List<Uri>,
        ttlSeconds: Long,
        onProgress: (index: Int, count: Int, written: Long, total: Long?) -> Unit,
        onDone: (BatchOutcome) -> Unit
    ) {
        if (uris.isEmpty()) {
            onDone(BatchOutcome(0, 0, null))
            return
        }
        var sent = 0
        var lastError: String? = null

        fun next(index: Int) {
            if (index >= uris.size) {
                onDone(BatchOutcome(sent, uris.size, lastError))
                return
            }
            val uri = uris[index]
            val name = displayName(context, uri)
            upload(
                context = context,
                baseUrl = baseUrl,
                token = token,
                uri = uri,
                displayName = name,
                ttlSeconds = ttlSeconds,
                onProgress = { written, total -> onProgress(index + 1, uris.size, written, total) },
                onResult = { ok, message ->
                    if (ok) {
                        sent++
                    } else {
                        lastError = "$name — $message"
                    }
                    next(index + 1)
                }
            )
        }

        next(0)
    }

    /**
     * @param onProgress bytes written so far and the total if the provider told us, else null
     * @param onResult (ok, message), always on the main thread
     */
    fun upload(
        context: Context,
        baseUrl: String,
        token: String?,
        uri: Uri,
        displayName: String,
        ttlSeconds: Long,
        onProgress: (written: Long, total: Long?) -> Unit,
        onResult: (ok: Boolean, message: String) -> Unit
    ) {
        Thread {
            val result = try {
                uploadBlocking(context, baseUrl, token, uri, displayName, ttlSeconds, onProgress)
            } catch (e: TooLargeException) {
                false to e.message!!
            } catch (e: FileNotFoundException) {
                false to "cannot open $displayName"
            } catch (e: IOException) {
                false to "upload failed (${e.message ?: "network error"})"
            } catch (e: Exception) {
                false to (e.message ?: e.javaClass.simpleName)
            }
            main.post { onResult(result.first, result.second) }
        }.apply { name = "teilen-file" }.start()
    }

    private fun uploadBlocking(
        context: Context,
        baseUrl: String,
        token: String?,
        uri: Uri,
        displayName: String,
        ttlSeconds: Long,
        onProgress: (Long, Long?) -> Unit
    ): Pair<Boolean, String> {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val declared = declaredSize(context, uri)

        // reject early on an honest length, but never trust it: the stream is counted too
        if (declared != null && declared > MAX_UPLOAD_BYTES) {
            throw TooLargeException("$displayName is ${humanSize(declared)} — the limit is 30 MB")
        }

        val boundary = "teilen-${System.currentTimeMillis()}"
        val url = URL("${Server.normalize(baseUrl)}/api/items")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            // a 30 MB upload over a slow link needs more than the usual read timeout
            readTimeout = 120_000
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
            authorize(token)
        }

        try {
            connection.outputStream.buffered().use { out ->
                writePart(out, boundary, "ttlSeconds", ttlSeconds.toString())
                writeFileHeader(out, boundary, displayName, mime)

                resolver.openInputStream(uri)?.use { input ->
                    copy(input, out) { written ->
                        if (written > MAX_UPLOAD_BYTES) {
                            throw TooLargeException("$displayName is over the 30 MB limit")
                        }
                        onProgress(written, declared)
                    }
                } ?: throw FileNotFoundException("no stream for $displayName")

                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            return finish(connection, "sent")
        } finally {
            connection.disconnect()
        }
    }

    /** counts as it copies, so the cap is enforced on the bytes and not on a header */
    private fun copy(input: InputStream, out: OutputStream, onBytes: (Long) -> Unit) {
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) {
                break
            }
            total += read
            if (total > MAX_UPLOAD_BYTES) {
                throw TooLargeException("file is over the 30 MB limit")
            }
            out.write(buffer, 0, read)
            onBytes(total)
        }
    }

    private fun writePart(out: OutputStream, boundary: String, name: String, value: String) {
        out.write("--$boundary\r\n".toByteArray())
        out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
        out.write(value.toByteArray(Charsets.UTF_8))
        out.write("\r\n".toByteArray())
    }

    private fun writeFileHeader(out: OutputStream, boundary: String, fileName: String, mime: String) {
        // strip quotes and newlines so the header cannot be broken by a hostile file name
        val safeName = fileName.replace("\"", "_").replace("\r", "_").replace("\n", "_")
        out.write("--$boundary\r\n".toByteArray())
        out.write("Content-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\n".toByteArray())
        out.write("Content-Type: $mime\r\n\r\n".toByteArray())
    }

    private fun finish(connection: HttpURLConnection, okMessage: String): Pair<Boolean, String> {
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.let { BufferedInputStream(it) }?.bufferedReader()?.use { it.readText() }.orEmpty()
        return if (status in 200..299) {
            true to okMessage
        } else if (status == 401) {
            // the backend's way of saying this device is no longer paired with anything
            false to UNPAIRED_MESSAGE
        } else {
            false to "server said $status: ${extractMessage(text)}"
        }
    }

    /** the one line of configuration that makes a call authenticated */
    private fun HttpURLConnection.authorize(token: String?) {
        if (token != null) {
            setRequestProperty("Authorization", "Bearer $token")
        }
    }

    private fun unreachable(baseUrl: String, e: Exception): String =
        "cannot reach ${Server.normalize(baseUrl)} (${e.message ?: "network error"})"

    // ------------------------------------------------ small JSON calls, no model layer

    /** a status code and, if the body was JSON, the body — that is all these calls need */
    private class Reply(val status: Int, val body: JSONObject?) {

        val ok: Boolean get() = status in 200..299

        fun str(name: String): String? = body?.optString(name)?.takeIf { it.isNotBlank() }

        fun num(name: String, fallback: Long): Long = when (val value = body?.opt(name)) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: fallback
            else -> fallback
        }

        /** the server's own wording, which beats a status code every time */
        val message: String get() = str("message") ?: "server said $status"
    }

    private fun postBlocking(baseUrl: String, path: String, token: String?, body: JSONObject): Reply {
        val connection = open(baseUrl, path, token, "POST")
        return try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            readReply(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun getBlocking(baseUrl: String, path: String): Reply {
        val connection = open(baseUrl, path, token = null, "GET")
        return try {
            readReply(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(baseUrl: String, path: String, token: String?, method: String): HttpURLConnection {
        val connection = (URL("${Server.normalize(baseUrl)}$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            setRequestProperty("Accept", "application/json")
            authorize(token)
        }
        return connection
    }

    /** the status code and the body as text, which is all a caller needs to pick a parser */
    private fun readBody(connection: HttpURLConnection): Pair<Int, String> {
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.let { BufferedInputStream(it) }?.bufferedReader()?.use { it.readText() }.orEmpty()
        return status to text
    }

    private fun readReply(connection: HttpURLConnection): Reply {
        val (status, text) = readBody(connection)
        val json = if (text.isBlank()) null else runCatching { JSONObject(text) }.getOrNull()
        return Reply(status, json)
    }

    /** every credential comes back in the same shape, whether it was minted or claimed */
    private fun signedInFrom(reply: Reply): SignedIn {
        val user = reply.body?.optJSONObject("user")
        return SignedIn(
            token = reply.str("token").orEmpty(),
            phone = user?.optString("phone")?.takeIf { it.isNotBlank() },
            deviceName = reply.str("deviceName"),
            deviceId = reply.str("deviceId")
        )
    }

    private fun declaredSize(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
    }.getOrNull()

    fun displayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
    }.getOrNull() ?: uri.lastPathSegment ?: "shared-file"

    fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    private fun extractMessage(body: String): String = try {
        JSONObject(body).optString("message").ifBlank { body.take(160) }
    } catch (e: Exception) {
        body.take(160)
    }

    private class TooLargeException(message: String) : IOException(message)
}
package com.teilen.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL

/**
 * The entire backend client: text POSTs and multipart uploads, no HTTP library.
 */
object TeilenApi {

    /** server-side cap; checked here too so an oversized file never leaves the phone */
    const val MAX_UPLOAD_BYTES = 30L * 1024 * 1024

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val BUFFER = 64 * 1024

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** @param onResult (ok, message), always on the main thread */
    fun send(
        baseUrl: String,
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
                false to "cannot reach ${Server.normalize(baseUrl)} (${e.message ?: "network error"})"
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
        uri: Uri,
        displayName: String,
        ttlSeconds: Long,
        onProgress: (written: Long, total: Long?) -> Unit,
        onResult: (ok: Boolean, message: String) -> Unit
    ) {
        Thread {
            val result = try {
                uploadBlocking(context, baseUrl, uri, displayName, ttlSeconds, onProgress)
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
        } else {
            false to "server said $status: ${extractMessage(text)}"
        }
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
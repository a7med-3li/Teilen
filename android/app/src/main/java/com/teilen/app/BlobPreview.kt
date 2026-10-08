package com.teilen.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thumbnails for image rows, written out longhand because the project carries no image library.
 *
 * The bytes come from the signed blob link, which by design needs no token, so this is the same
 * fetch the web app's `<img>` does. Decoding is scaled down on a worker thread: a phone photo is
 * far larger than a list row, and holding several at full size is how a list gets an OOM.
 *
 * List rows recycle their views, so every request stamps the view with the url it is filling and
 * the result is thrown away unless the stamp still matches when the download lands.
 */
object BlobPreview {

    /** long edge of a row thumbnail, in pixels; enough for a phone screen and no more */
    private const val THUMBNAIL_PX = 480

    /** the full-screen viewer wants more than a row, but still not the original camera file */
    private const val LARGE_PX = 2048

    /** an oversized download is refused rather than allowed to exhaust the heap */
    private const val MAX_DOWNLOAD_BYTES = 24 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private val inFlight = HashSet<String>()

    fun load(view: ImageView, url: String) = load(view, url, THUMBNAIL_PX)

    /** same fetch, decoded for a screen rather than for a list row */
    fun loadLarge(view: ImageView, url: String) = load(view, url, LARGE_PX)

    private fun load(view: ImageView, url: String, maxPx: Int) {
        // the cache is keyed by size too, so opening an image full screen does not serve the row
        val key = "$maxPx|$url"
        view.tag = key
        cache.get(key)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageDrawable(null)
        synchronized(inFlight) {
            if (!inFlight.add(key)) {
                return
            }
        }
        Thread {
            val bitmap = runCatching { fetch(url, maxPx) }.getOrNull()
            if (bitmap != null) {
                cache.put(key, bitmap)
            }
            synchronized(inFlight) {
                inFlight.remove(key)
            }
            main.post {
                if (bitmap != null && view.tag == key) {
                    view.setImageBitmap(bitmap)
                }
            }
        }.apply { name = "teilen-thumb" }.start()
    }

    /**
     * Download once, decode twice: the first pass only reads the header to learn the real size, the
     * second decodes at a fraction of it. The bytes are held in memory between the two, which is
     * cheaper than a second request and avoids the trap where a spent input stream decodes to null.
     */
    private fun fetch(url: String, maxPx: Int): Bitmap? {
        val bytes = download(url) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(ByteArrayInputStream(bytes), null, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, maxPx)
        }
        return BitmapFactory.decodeStream(ByteArrayInputStream(bytes), null, options)
    }

    private fun download(url: String): ByteArray? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            useCaches = false
        }
        return try {
            if (connection.responseCode !in 200..299) {
                return null
            }
            val declared = connection.contentLength
            if (declared > MAX_DOWNLOAD_BYTES) {
                return null
            }
            connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(declared.coerceAtLeast(16 * 1024))
                val buffer = ByteArray(32 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) {
                        break
                    }
                    total += read
                    if (total > MAX_DOWNLOAD_BYTES) {
                        return null
                    }
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /** the largest power of two that keeps both edges at or above [maxPx] */
    private fun sampleFor(width: Int, height: Int, maxPx: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= maxPx && h / 2 >= maxPx) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }
}
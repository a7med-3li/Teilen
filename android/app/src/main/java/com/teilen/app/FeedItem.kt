package com.teilen.app

import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/**
 * One row of the feed, in the shape the backend sends it.
 *
 * `GET /api/items` answers with the whole live feed for the account the token belongs to, newest
 * first, so every device renders the same list and there is nothing to merge or reconcile.
 */
data class FeedItem(
    val id: String,
    val type: String,
    val content: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val createdAt: Instant?,
    val expiresAt: Instant?,
    /**
     * A signed path to the bytes on the server, or null for text and links, which carry their
     * payload inline. The signature is what lets a plain image or PDF viewer open the file without
     * ever seeing the account's token.
     */
    val blobPath: String?
) {

    val hasBlob: Boolean get() = !blobPath.isNullOrBlank()

    /** where a viewer app can fetch the bytes from, header and all */
    fun blobUrl(baseUrl: String): String? = blobPath?.let { Server.normalize(baseUrl) + it }

    /** an image gets a thumbnail in the list; nothing else does */
    val isImage: Boolean get() = type == "IMAGE"

    /** the small caps label on the row */
    val kind: String
        get() = when (type) {
            "IMAGE" -> "image"
            "PDF" -> "pdf"
            "FILE" -> "file"
            "LINK" -> "link"
            else -> "text"
        }

    /** what the row shows as its title: a file name, or the first line of what was written */
    val title: String
        get() = when {
            hasBlob -> content
            else -> content.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: content
        }

    /** the muted second line: how big, and how long it is still here for */
    fun meta(now: Instant = Instant.now()): String {
        val bits = ArrayList<String>(2)
        if (sizeBytes != null && sizeBytes > 0) {
            bits += TeilenApi.humanSize(sizeBytes)
        }
        val expiry = expiresAt
        if (expiry != null) {
            val left = Duration.between(now, expiry)
            bits += if (left.isNegative) "gone" else "left ${coarse(left)}"
        }
        return bits.joinToString(" · ")
    }

    private fun coarse(duration: Duration): String {
        val minutes = duration.toMinutes()
        return when {
            minutes < 1 -> "under a minute"
            minutes < 60 -> "$minutes min"
            minutes < 60 * 24 -> "${duration.toHours()} h"
            else -> "${duration.toDays()} d"
        }
    }

    companion object {

        /** null when the row is missing the one field nothing else can do without */
        fun parse(json: JSONObject): FeedItem? {
            val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
            return FeedItem(
                id = id,
                type = json.optString("type").ifBlank { "TEXT" },
                content = json.optString("content"),
                mimeType = json.optString("mimeType").takeIf { it.isNotBlank() },
                sizeBytes = json.optLong("sizeBytes").takeIf { json.has("sizeBytes") },
                createdAt = instant(json.optString("createdAt")),
                expiresAt = instant(json.optString("expiresAt")),
                blobPath = json.optString("blobUrl").takeIf { it.isNotBlank() }
            )
        }

        private fun instant(raw: String): Instant? =
            if (raw.isBlank()) null else runCatching { Instant.parse(raw) }.getOrNull()
    }
}
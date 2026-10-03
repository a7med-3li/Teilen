package com.teilen.app

import android.app.Activity
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast

/**
 * Started by the "Send" notification action.
 *
 * The tap is what puts this app in the foreground, which is what makes the clipboard
 * readable on Android 10+ — that is the whole reason this exists as an activity.
 * If the read is somehow refused, the copy the service spotted is used as a fallback.
 */
class SendClipboardActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = readClipboard() ?: ClipboardWatchService.lastCopy
        if (text.isNullOrBlank()) {
            Toast.makeText(this, R.string.nothing_to_send, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        ClipboardWatchService.lastCopy = text
        TeilenApi.send(Server.get(this), text, DEFAULT_TTL_SECONDS) { ok, message ->
            if (ok) {
                Toast.makeText(this, R.string.copied_toast, Toast.LENGTH_SHORT).show()
                ClipboardWatchService.reset(this)
                ClipboardWatchService.lastCopy = null
            } else {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }

    private fun readClipboard(): String? = runCatching {
        getSystemService(ClipboardManager::class.java)
            .primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.text
            ?.toString()
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private companion object {
        const val DEFAULT_TTL_SECONDS = 1800L
    }
}

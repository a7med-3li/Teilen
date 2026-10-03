package com.teilen.app

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
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
        val token = Session.token(this)
        if (token == null) {
            // nothing can be sent without a token; the copy is kept so the tap is not wasted
            Toast.makeText(this, R.string.pair_before_sharing, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
            finish()
            return
        }

        TeilenApi.send(Server.get(this), token, text, DEFAULT_TTL_SECONDS) { ok, message ->
            if (ok) {
                Toast.makeText(this, R.string.copied_toast, Toast.LENGTH_SHORT).show()
                ClipboardWatchService.reset(this)
                ClipboardWatchService.lastCopy = null
            } else {
                if (message == TeilenApi.UNPAIRED_MESSAGE) {
                    Session.clear(this)
                }
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

package com.teilen.app

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.widget.Toast

/**
 * Android 10+ compliant trampoline activity.
 *
 * In Android 10+ (API 29–36), background apps and foreground services cannot read
 * [ClipboardManager.getPrimaryClip] unless the app currently has window focus.
 * When the user taps the persistent notification action, this transparent activity is brought
 * to the foreground. Once it gains window focus in [onWindowFocusChanged], it reads the primary clip,
 * triggers tactile haptic feedback, dispatches the transfer to [TransferService], and finishes (<40ms).
 */
class ClipboardCaptureActivity : Activity() {

    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Focus will be confirmed in onWindowFocusChanged
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true

        window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

        val text = readClipboard() ?: ClipboardWatchService.lastCopy
        if (text.isNullOrBlank()) {
            Toast.makeText(this, R.string.nothing_to_send, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        ClipboardWatchService.lastCopy = text
        val token = Session.token(this)
        if (token == null) {
            Toast.makeText(this, R.string.pair_before_sharing, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
            finish()
            return
        }

        Toast.makeText(this, R.string.sending_toast, Toast.LENGTH_SHORT).show()
        TransferService.start(
            context = this,
            text = text,
            fds = null,
            names = null,
            mimes = null,
            sizes = null,
            ttlSeconds = DEFAULT_TTL_SECONDS
        )
        ClipboardWatchService.reset(this)
        ClipboardWatchService.lastCopy = null
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
        const val DEFAULT_TTL_SECONDS = 3600L
    }
}

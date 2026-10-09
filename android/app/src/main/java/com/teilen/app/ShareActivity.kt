package com.teilen.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.util.ArrayList

/**
 * What the system share sheet starts. Since Phase 2 the upload itself does not happen here: a
 * paired share is handed to [TransferService], which keeps going after this activity is gone, with
 * progress in the notification shade instead of a window.
 *
 * This activity only opens the files (the read grant for a shared URI dies with its task; a
 * descriptor does not), gives an immediate signal, and gets out of the way. It shows a window only
 * when it has something to say: not paired yet, or nothing to send.
 *
 * Registered for every MIME type (text, links, images, PDFs, anything) so Teilen is always one
 * tap away, plus ACTION_SEND_MULTIPLE for multi-select. Text goes as text; files are uploaded
 * as multipart, one feed item each, up to the 30 MB the server accepts.
 */
class ShareActivity : Activity() {

    private var title: TextView? = null
    private var status: TextView? = null
    private var spinner: ProgressBar? = null
    private var progress: ProgressBar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dispatch(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatch(intent)
    }

    private fun dispatch(incoming: Intent?) {
        if (incoming == null) {
            finish()
            return
        }
        val text = textOf(incoming)
        val uris = streamUris(incoming)

        // nothing can be sent without a token, so the share goes straight to the account card
        // instead of bouncing off a 401 — with the text handed over, so nothing is lost
        if (!Session.isPaired(this)) {
            showCard()
            status?.text = getString(
                if (!uris.isNullOrEmpty()) R.string.pair_before_files else R.string.pair_before_sharing
            )
            handOver(incoming, text)
            return
        }

        if (uris.isNullOrEmpty() && text.isNullOrBlank()) {
            showCard()
            finishWith(getString(R.string.share_empty))
            return
        }

        if (!dispatchToService(text, uris)) {
            showCard()
            finishWith(getString(R.string.share_empty))
            return
        }

        // the instant acknowledgement, then gone: the notification carries the rest
        haptic()
        Toast.makeText(this, R.string.sending_toast, Toast.LENGTH_SHORT).show()
        finish()
    }

    /**
     * Opens every stream here, on purpose: a shared URI's read grant is tied to this activity's
     * task and would be revoked the moment it finishes. A descriptor travels with the intent and
     * lets the service read the file long after this activity is gone.
     *
     * @return false only when there is genuinely nothing the service could send
     */
    private fun dispatchToService(text: String?, uris: List<Uri>?): Boolean {
        if (uris.isNullOrEmpty()) {
            TransferService.start(this, text.orEmpty(), null, null, null, null, DEFAULT_TTL_SECONDS)
            return true
        }

        val fds = ArrayList<ParcelFileDescriptor>()
        val names = ArrayList<String>()
        val mimes = ArrayList<String>()
        val sizes = ArrayList<Long>()
        for (uri in uris) {
            val descriptor = runCatching { contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
            if (descriptor == null) {
                continue
            }
            fds.add(descriptor)
            names.add(TeilenApi.displayName(this, uri))
            mimes.add(contentResolver.getType(uri) ?: "application/octet-stream")
            sizes.add(TeilenApi.declaredSize(this, uri) ?: -1L)
        }

        if (fds.isEmpty()) {
            // no file could be opened; a text that rode along can still be sent
            if (!text.isNullOrBlank()) {
                TransferService.start(this, text, null, null, null, null, DEFAULT_TTL_SECONDS)
                return true
            }
            return false
        }

        // streams go first: file managers attach the name in EXTRA_TEXT, and reading the text
        // first sent the name and silently dropped the file
        TransferService.start(
            this,
            text,
            fds,
            names,
            mimes,
            sizes.toLongArray(),
            DEFAULT_TTL_SECONDS
        )
        return true
    }

    /**
     * Hands the share over to the full screen, carrying the text so it can be sent once the account
     * exists. Files cannot travel this way — the URI grant belongs to this task — so they are
     * simply refused, with the reason on screen rather than a silent nothing.
     */
    private fun handOver(incoming: Intent, text: String?) {
        val onwards = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (!text.isNullOrBlank()) {
            onwards.putExtra(MainActivity.EXTRA_TEXT, text)
        }
        startActivity(onwards)
        finishSoon()
    }

    private fun showCard() {
        if (title != null) {
            return
        }
        setContentView(R.layout.activity_share)
        title = findViewById(R.id.share_title)
        status = findViewById(R.id.share_status)
        spinner = findViewById(R.id.share_spinner)
        progress = findViewById(R.id.share_progress)
    }

    private fun haptic() {
        val view = window?.decorView ?: return
        val feedback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        }
        view.performHapticFeedback(feedback)
    }

    /** EXTRA_TEXT plus EXTRA_SUBJECT, which is how mail clients share a link's title alongside it */
    private fun textOf(intent: Intent): String? {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) {
            return null
        }
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        return when {
            text.isEmpty() && subject.isEmpty() -> null
            subject.isNotEmpty() && !text.contains(subject) -> "$subject\n$text".trim()
            else -> text
        }
    }

    /** content:// URIs handed over by other apps — not filesystem paths */
    private fun streamUris(intent: Intent): List<Uri>? {
        val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        }
        return uris?.filterNotNull()
    }

    private fun finishWith(message: String) {
        status?.text = message
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finishSoon()
    }

    private fun finishSoon() {
        window.decorView.postDelayed({ finish() }, 600)
    }

    private companion object {
        const val DEFAULT_TTL_SECONDS = 1800L
    }
}

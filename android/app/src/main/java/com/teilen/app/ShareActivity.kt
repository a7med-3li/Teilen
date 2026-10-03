package com.teilen.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * What the system share sheet starts. It grabs the content, pushes it, and gets out of the way —
 * no window the user has to look at unless something goes wrong.
 *
 * Registered for every MIME type (text, links, images, PDFs, anything) so Teilen is always one
 * tap away, plus ACTION_SEND_MULTIPLE for multi-select. Text goes as text; files are uploaded
 * as multipart, one feed item each, up to the 30 MB the server accepts.
 */
class ShareActivity : Activity() {

    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share)

        title = findViewById(R.id.share_title)
        status = findViewById(R.id.share_status)
        spinner = findViewById(R.id.share_spinner)
        progress = findViewById(R.id.share_progress)

        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        val text = textOf(intent)
        val uris = streamUris(intent)

        // nothing can be sent without a token, so the share goes straight to the account card
        // instead of bouncing off a 401 — with the text handed over, so nothing is lost
        if (!Session.isPaired(this)) {
            status.text = getString(
                if (!uris.isNullOrEmpty()) R.string.pair_before_files else R.string.pair_before_sharing
            )
            handOver(intent, text)
            return
        }

        // text wins: some apps attach both a snippet and a preview URI
        if (!text.isNullOrBlank()) {
            sendText(text)
        } else if (!uris.isNullOrEmpty()) {
            sendFiles(uris)
        } else {
            finishWith(getString(R.string.share_empty))
        }
    }

    private fun sendText(text: String) {
        title.text = getString(R.string.app_name)
        status.text = getString(R.string.share_sending, text.take(80))
        TeilenApi.send(Server.get(this), Session.token(this), text, DEFAULT_TTL_SECONDS) { ok, message ->
            if (ok) {
                status.text = getString(R.string.share_sent)
                Toast.makeText(this, R.string.sent_toast, Toast.LENGTH_SHORT).show()
                finishSoon()
            } else {
                // hand the text back to the full screen so nothing is lost
                status.text = message
                if (message == TeilenApi.UNPAIRED_MESSAGE) {
                    // the token is gone: forget it, or the app looks paired and is not
                    Session.clear(this)
                }
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(MainActivity.EXTRA_TEXT, text)
                )
                finish()
            }
        }
    }

    private fun sendFiles(uris: List<Uri>) {
        title.text = if (uris.size == 1) {
            getString(R.string.share_uploading, TeilenApi.displayName(this, uris.first()))
        } else {
            getString(R.string.share_uploading_n, uris.size)
        }
        status.text = getString(R.string.share_sending, getString(R.string.app_name))

        TeilenApi.uploadAll(
            context = this,
            baseUrl = Server.get(this),
            token = Session.token(this),
            uris = uris,
            ttlSeconds = DEFAULT_TTL_SECONDS,
            onProgress = { index, count, written, total ->
                // one file is already finished or in flight: show where the current one stands
                if (total != null && total > 0) {
                    progress.visibility = View.VISIBLE
                    progress.progress = ((written * 100) / total).toInt()
                    status.text = getString(
                        R.string.share_uploading_progress,
                        TeilenApi.humanSize(written),
                        TeilenApi.humanSize(total)
                    )
                } else {
                    progress.visibility = View.GONE
                    status.text = if (written > 0) {
                        getString(R.string.uploading_bytes, TeilenApi.humanSize(written))
                    } else {
                        getString(R.string.sending)
                    }
                }
                if (count > 1) {
                    title.text = getString(R.string.uploading_n, count, index, count)
                }
            },
            onDone = { outcome ->
                if (outcome.failed == 0) {
                    status.text = if (outcome.sent == 1) {
                        getString(R.string.share_sent)
                    } else {
                        getString(R.string.share_files_sent, outcome.sent)
                    }
                    Toast.makeText(this, R.string.sent_toast, Toast.LENGTH_SHORT).show()
                    finishSoon()
                } else {
                    // stay open on a failure: the reason is the whole point of the window
                    progress.visibility = View.GONE
                    spinner.visibility = View.GONE
                    status.text = getString(R.string.file_failed, outcome.lastError ?: "")
                }
            }
        )
    }

    /**
     * Hands the share over to the full screen, carrying the text so it can be sent once the account
     * exists. Files cannot travel this way — the URI grant belongs to this task — so they are
     * simply refused, with the reason on screen rather than a silent nothing.
     */
    private fun handOver(intent: Intent, text: String?) {
        val onwards = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (!text.isNullOrBlank()) {
            onwards.putExtra(MainActivity.EXTRA_TEXT, text)
        }
        startActivity(onwards)
        finishSoon()
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
        status.text = message
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
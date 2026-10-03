package com.teilen.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * "Copy to Teilen" in the text-selection toolbar that Android shows inside every app
 * (WhatsApp, Chrome, Notes, whatever you are reading in).
 *
 * PROCESS_TEXT normally means "replace the selection with something", so the very same
 * text is handed straight back: the source app is left untouched and the user only sees
 * a toast. That makes it a copy, not a transformation.
 */
class ProcessTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val selected = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        if (selected.isBlank()) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, selected))

        TeilenApi.send(Server.get(this), Session.token(this), selected, DEFAULT_TTL_SECONDS) { ok, message ->
            if (ok) {
                Toast.makeText(this, R.string.copied_toast, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }

    private companion object {
        const val DEFAULT_TTL_SECONDS = 1800L
    }
}

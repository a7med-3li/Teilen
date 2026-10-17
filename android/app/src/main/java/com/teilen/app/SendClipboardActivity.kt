package com.teilen.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Trampoline forwarding to [ClipboardCaptureActivity].
 */
class SendClipboardActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, ClipboardCaptureActivity::class.java))
        finish()
    }
}

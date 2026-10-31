package com.teilen.app

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.core.content.IntentCompat
import java.util.ArrayList

/**
 * 100% Zero-Friction Headless Share Sheet Receiver.
 *
 * Replaces MainActivity and ShareActivity as the system share sheet entry point.
 * Runs with Theme.Translucent.NoTitleBar and zero window transitions.
 * Extracts payloads (prioritizing file streams over fallback text captions),
 * transfers content URI permissions to TransferService via ClipData + FLAG_GRANT_READ_URI_PERMISSION,
 * opens ParcelFileDescriptors for immediate survivability, triggers a subtle haptic tick,
 * and calls finish() synchronously in < 10ms so the user remains inside WhatsApp / host app.
 */
class ShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        overridePendingTransition(0, 0)
        super.onCreate(savedInstanceState)
        handleIncoming(intent)
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        overridePendingTransition(0, 0)
        handleIncoming(intent)
        finish()
        overridePendingTransition(0, 0)
    }

    private fun handleIncoming(incoming: Intent?) {
        if (incoming == null) return

        val uris = extractStreamUris(incoming)
        // Strictly prioritize file streams over fallback text captions
        val text = if (uris.isNullOrEmpty()) extractText(incoming) else null

        if (uris.isNullOrEmpty() && text.isNullOrBlank()) {
            Toast.makeText(this, R.string.share_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val serviceIntent = Intent(this, TransferService::class.java).apply {
            action = TransferService.ACTION_TRANSFER
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        if (!uris.isNullOrEmpty()) {
            // Transfer temporary URI permissions across finish() to TransferService
            val clip = ClipData.newRawUri("shared_file", uris[0])
            for (i in 1 until uris.size) {
                clip.addItem(ClipData.Item(uris[i]))
            }
            serviceIntent.clipData = clip
            serviceIntent.putParcelableArrayListExtra(TransferService.EXTRA_URIS, ArrayList(uris))

            // Open descriptors immediately so they survive activity finish without any permission loss
            val fds = ArrayList<ParcelFileDescriptor>()
            val names = ArrayList<String>()
            val mimes = ArrayList<String>()
            val sizes = ArrayList<Long>()

            var maxDeclaredSize = 0L
            for (uri in uris) {
                val descriptor = runCatching { contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
                if (descriptor != null) {
                    fds.add(descriptor)
                    val name = TeilenApi.displayName(this, uri)
                    names.add(name)
                    mimes.add(contentResolver.getType(uri) ?: "application/octet-stream")
                    val size = TeilenApi.declaredSize(this, uri) ?: -1L
                    sizes.add(size)
                    if (size > maxDeclaredSize) maxDeclaredSize = size
                }
            }

            if (fds.isNotEmpty()) {
                serviceIntent.putParcelableArrayListExtra(TransferService.EXTRA_FDS, fds)
                serviceIntent.putStringArrayListExtra(TransferService.EXTRA_NAMES, names)
                serviceIntent.putStringArrayListExtra(TransferService.EXTRA_MIMES, mimes)
                serviceIntent.putExtra(TransferService.EXTRA_SIZES, sizes.toLongArray())
            }

            // If file > 2 MB, show immediate non-intrusive feedback
            if (maxDeclaredSize > 2 * 1024 * 1024L) {
                Toast.makeText(this, getString(R.string.toast_sending_headless), Toast.LENGTH_SHORT).show()
            }
        } else if (!text.isNullOrBlank()) {
            serviceIntent.putExtra(TransferService.EXTRA_TEXT, text)
        }

        // Trigger subtle haptic tick
        haptic()

        // Start background service
        startForegroundService(serviceIntent)
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

    private fun extractText(intent: Intent): String? {
        if (intent.action == Intent.ACTION_PROCESS_TEXT) {
            val pt = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            if (!pt.isNullOrBlank()) return pt
        }
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

    private fun extractStreamUris(intent: Intent): List<Uri>? {
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val list = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            val filtered = list?.filterNotNull().orEmpty()
            if (filtered.isNotEmpty()) return filtered
            val clipList = (0 until (intent.clipData?.itemCount ?: 0)).mapNotNull { intent.clipData?.getItemAt(it)?.uri }
            return clipList.ifEmpty { null }
        }
        val single = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?: (if (intent.type != "text/plain") intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri else null)
            ?: (if (intent.data != null && (intent.data?.scheme == "content" || intent.data?.scheme == "file")) intent.data else null)
        return single?.let { listOf(it) }
    }
}

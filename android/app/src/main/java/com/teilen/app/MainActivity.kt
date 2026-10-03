package com.teilen.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * The manual path: open the app, paste a text or pick a file, send.
 */
class MainActivity : Activity() {

    private val ttls = listOf(60L, 300L, 900L, 1800L, 3600L, 14400L)

    private lateinit var content: EditText
    private lateinit var spinner: Spinner
    private lateinit var status: TextView
    private lateinit var server: EditText
    private lateinit var send: Button
    private lateinit var pickFile: Button
    private lateinit var fileProgress: ProgressBar
    private lateinit var fileStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        content = findViewById(R.id.content)
        spinner = findViewById(R.id.ttl)
        status = findViewById(R.id.status)
        server = findViewById(R.id.server)
        send = findViewById(R.id.send)
        pickFile = findViewById(R.id.pick_file)
        fileProgress = findViewById(R.id.file_progress)
        fileStatus = findViewById(R.id.file_status)

        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            ttls.map { Server.label(it) }
        )

        server.setText(Server.get(this))
        status.text = getString(R.string.ready, Server.get(this))

        findViewById<Button>(R.id.save_server).setOnClickListener {
            val url = Server.normalize(server.text.toString())
            if (url.isBlank()) {
                status.text = getString(R.string.server_blank)
            } else {
                Server.set(this, url)
                server.setText(url)
                status.text = getString(R.string.server_saved, url)
            }
        }

        send.setOnClickListener { submit() }

        pickFile.setOnClickListener {
            // ACTION_OPEN_DOCUMENT needs no storage permission and hands back a readable URI
            val open = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            startActivityForResult(open, REQ_OPEN_DOCUMENT)
        }

        setupClipboardWatch()

        // arriving from the share sheet when the upload failed: the text is handed over
        intent?.getStringExtra(EXTRA_TEXT)?.let {
            content.setText(it)
            intent.removeExtra(EXTRA_TEXT)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_STOP_WATCHING, false)) {
            setWatching(false)
        }
    }

    @Deprecated("platform activities only; there is no AndroidX result API here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_OPEN_DOCUMENT || resultCode != RESULT_OK || data == null) {
            return
        }
        val picked = pickedUris(data)
        if (picked.isEmpty()) {
            return
        }
        // hold on to the read grant for as long as this screen is around
        picked.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        uploadFiles(picked)
    }

    private fun pickedUris(data: Intent): List<Uri> {
        val single = data.data
        val many = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            data.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            data.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        }
        return many?.filterNotNull() ?: listOfNotNull(single)
    }

    private fun uploadFiles(uris: List<Uri>) {
        val url = Server.normalize(server.text.toString())
        Server.set(this, url)
        setBusy(true)
        showFileStatus(getString(R.string.uploading, TeilenApi.displayName(this, uris.first())), false)
        fileProgress.visibility = View.VISIBLE
        fileProgress.progress = 0

        TeilenApi.uploadAll(
            context = this,
            baseUrl = url,
            uris = uris,
            ttlSeconds = ttls[spinner.selectedItemPosition],
            onProgress = { index, count, written, total ->
                if (total != null && total > 0) {
                    fileProgress.progress = ((written * 100) / total).toInt()
                    showFileStatus(
                        getString(
                            R.string.uploading_n,
                            count,
                            index,
                            count
                        ) + " · " + getString(
                            R.string.uploading_bytes,
                            TeilenApi.humanSize(written)
                        ),
                        false
                    )
                } else if (written > 0) {
                    showFileStatus(
                        getString(R.string.uploading_bytes, TeilenApi.humanSize(written)),
                        false
                    )
                }
            },
            onDone = { outcome ->
                setBusy(false)
                fileProgress.visibility = View.GONE
                if (outcome.failed == 0) {
                    val message = if (outcome.sent == 1) {
                        getString(R.string.file_sent, uris.first().let { TeilenApi.displayName(this, it) })
                    } else {
                        getString(R.string.files_sent, outcome.sent)
                    }
                    showFileStatus(message, false)
                    Toast.makeText(this, R.string.sent_toast, Toast.LENGTH_SHORT).show()
                } else {
                    showFileStatus(getString(R.string.file_failed, outcome.lastError ?: ""), true)
                }
            }
        )
    }

    private fun showFileStatus(text: String, isError: Boolean) {
        fileStatus.visibility = View.VISIBLE
        fileStatus.text = text
        fileStatus.setTextColor(
            getColor(if (isError) R.color.danger else R.color.text_secondary)
        )
    }

    private fun setupClipboardWatch() {
        val watch = findViewById<Switch>(R.id.watch)
        watch.isChecked = ClipboardWatchService.isWatching(this)

        watch.setOnCheckedChangeListener { button, checked ->
            if (!button.isPressed) {
                return@setOnCheckedChangeListener
            }
            setWatching(checked)
            if (checked) {
                askForNotificationPermission()
                ClipboardWatchService.start(this)
                status.text = getString(R.string.watching_on)
            } else {
                ClipboardWatchService.stop(this)
                status.text = getString(R.string.watching_off)
            }
        }

        findViewById<Button>(R.id.try_clipboard).setOnClickListener {
            val sample = getString(R.string.watch_offer_text) + " — Teilen"
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("Teilen", sample))
            Toast.makeText(this, R.string.copied_sample, Toast.LENGTH_LONG).show()
        }
    }

    private fun setWatching(watching: Boolean) {
        ClipboardWatchService.setWatching(this, watching)
        findViewById<Switch>(R.id.watch).isChecked = watching
        if (!watching) {
            ClipboardWatchService.stop(this)
        }
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATIONS && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            // without notifications there is no button to tap, so the feature is pointless
            setWatching(false)
            Toast.makeText(this, R.string.notifications_needed, Toast.LENGTH_LONG).show()
        }
    }

    private fun submit() {
        val text = content.text.toString().trim()
        if (text.isEmpty()) {
            status.text = getString(R.string.nothing_to_send)
            return
        }

        val url = Server.normalize(server.text.toString())
        Server.set(this, url)

        setBusy(true)
        TeilenApi.send(url, text, ttls[spinner.selectedItemPosition]) { ok, message ->
            setBusy(false)
            if (ok) {
                content.setText("")
                status.text = getString(R.string.sent, message)
                Toast.makeText(this, R.string.sent_toast, Toast.LENGTH_SHORT).show()
            } else {
                status.text = message
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        send.isEnabled = !busy
        send.text = getString(if (busy) R.string.sending else R.string.send)
        pickFile.isEnabled = !busy
    }

    companion object {
        const val EXTRA_TEXT = "teilen.extra.text"
        const val EXTRA_STOP_WATCHING = "teilen.extra.stop_watching"
        private const val REQ_NOTIFICATIONS = 41
        private const val REQ_OPEN_DOCUMENT = 42
    }
}
package com.teilen.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.FileInputStream

/**
 * The actual share upload, run away from any screen so it survives the user leaving the share
 * sheet, with its progress in the notification shade instead of an activity window.
 *
 * File descriptors arrive already open rather than as content:// URIs: the read grant for a shared
 * URI lives only as long as the sharing activity's task, so only a descriptor can outlive the tap.
 * Text rides along as its own item, exactly as the share sheet used to send it.
 */
class TransferService : Service() {

    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        TeilenNotifications.ensureTransferChannel(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_TRANSFER) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startInForeground(workingNotification(getString(R.string.sending)))

        val text = intent.getStringExtra(EXTRA_TEXT)
        val fds = fdsOf(intent)
        val names = intent.getStringArrayListExtra(EXTRA_NAMES).orEmpty()
        val mimes = intent.getStringArrayListExtra(EXTRA_MIMES).orEmpty()
        val sizes = sizesOf(intent, fds.size)
        val ttl = intent.getLongExtra(EXTRA_TTL, DEFAULT_TTL_SECONDS)

        transfer(text, fds, names, mimes, sizes, ttl)
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ the work

    private fun transfer(
        text: String?,
        fds: List<ParcelFileDescriptor>,
        names: List<String>,
        mimes: List<String>,
        sizes: List<Long?>,
        ttl: Long
    ) {
        if (fds.isEmpty()) {
            sendText(text.orEmpty(), ttl)
            return
        }
        // a share that carried both text and files becomes two items; text goes first
        if (!text.isNullOrBlank()) {
            TeilenApi.send(Server.get(this), Session.token(this), text, ttl) { _, _ ->
                sendFiles(fds, names, mimes, sizes, ttl)
            }
        } else {
            sendFiles(fds, names, mimes, sizes, ttl)
        }
    }

    private fun sendText(text: String, ttl: Long) {
        if (text.isBlank()) {
            done(getString(R.string.share_empty), ok = false)
            return
        }
        TeilenApi.send(Server.get(this), Session.token(this), text, ttl) { ok, message ->
            done(if (ok) getString(R.string.share_sent) else message, ok)
        }
    }

    private fun sendFiles(
        fds: List<ParcelFileDescriptor>,
        names: List<String>,
        mimes: List<String>,
        sizes: List<Long?>,
        ttl: Long
    ) {
        // The direct LAN path carries exactly one envelope, so a single-file share tries it first
        // and falls back to the ordinary upload within 2.5 s. A batch goes straight to the server:
        // one peer connection per file would cost a fallback window each.
        if (fds.size == 1) {
            val name = names.getOrNull(0) ?: "file"
            val mime = mimes.getOrNull(0) ?: "application/octet-stream"
            val size = sizes.getOrNull(0) ?: -1L
            val engine = LanTransferEngine(this)
            engine.attempt(LanTransferEngine.Payload(name, mime, size) { inputOf(fds[0]) }) { sent ->
                if (sent) {
                    done(getString(R.string.share_sent), ok = true)
                } else {
                    // the LAN read may have moved the descriptor; the server upload starts at zero
                    rewind(fds)
                    uploadViaHttp(fds, names, mimes, sizes, ttl)
                }
            }
            return
        }
        uploadViaHttp(fds, names, mimes, sizes, ttl)
    }

    private fun uploadViaHttp(
        fds: List<ParcelFileDescriptor>,
        names: List<String>,
        mimes: List<String>,
        sizes: List<Long?>,
        ttl: Long
    ) {
        val count = fds.size
        TeilenApi.uploadAllFds(
            baseUrl = Server.get(this),
            token = Session.token(this),
            fds = fds,
            names = names,
            mimes = mimes,
            sizes = sizes,
            ttlSeconds = ttl,
            onProgress = { index, _, written, total -> onProgress(index, count, written, total) },
            onDone = { outcome ->
                val message = when {
                    outcome.failed == 0 && outcome.sent == 1 -> getString(R.string.share_sent)
                    outcome.failed == 0 -> getString(R.string.share_files_sent, outcome.sent)
                    else -> getString(R.string.file_failed, outcome.lastError ?: "")
                }
                done(message, outcome.failed == 0)
            }
        )
    }

    /** a fresh read view of a shared descriptor, without duplicating the descriptor itself */
    private fun inputOf(fd: ParcelFileDescriptor): FileInputStream =
        FileInputStream(fd.fileDescriptor)

    /** resets a shared descriptor to the start so the fallback reads the file again from byte zero */
    private fun rewind(fds: List<ParcelFileDescriptor>) {
        fds.forEach { fd ->
            runCatching { Os.lseek(fd.fileDescriptor, 0, OsConstants.SEEK_SET) }
        }
    }

    // ------------------------------------------------------------------ notifications

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(TeilenNotifications.TRANSFER_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(TeilenNotifications.TRANSFER_ID, notification)
        }
    }

    private fun workingNotification(text: String): Notification =
        TeilenNotifications.transferBuilder(this)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .setContentIntent(openApp())
            .build()

    private fun onProgress(index: Int, count: Int, written: Long, total: Long?) {
        val builder = TeilenNotifications.transferBuilder(this)
            .setContentTitle(getString(R.string.app_name))
            .setOngoing(true)
            .setContentIntent(openApp())
        if (total != null && total > 0) {
            val percent = ((written * 100) / total).toInt().coerceIn(0, 100)
            builder
                .setContentText(
                    getString(
                        R.string.share_uploading_progress,
                        TeilenApi.humanSize(written),
                        TeilenApi.humanSize(total)
                    )
                )
                .setProgress(100, percent, false)
        } else {
            builder
                .setContentText(
                    if (count > 1) getString(R.string.share_uploading_n, count) else getString(R.string.sending)
                )
                .setProgress(0, 0, true)
        }
        manager().notify(TeilenNotifications.TRANSFER_ID, builder.build())
    }

    /** the last word: detach the ongoing notice and let the outcome replace it, then stop */
    private fun done(message: String, ok: Boolean) {
        val notification = TeilenNotifications.transferBuilder(this)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .setProgress(0, 0, false)
            .build()
        stopForeground(STOP_FOREGROUND_DETACH)
        manager().notify(TeilenNotifications.TRANSFER_ID, notification)
        stopSelf()
    }

    private fun manager() = getSystemService(NotificationManager::class.java)

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // ------------------------------------------------------------------ intent plumbing

    private fun fdsOf(intent: Intent): List<ParcelFileDescriptor> {
        val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(EXTRA_FDS, ParcelFileDescriptor::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<ParcelFileDescriptor>(EXTRA_FDS)
        }
        return list?.filterNotNull().orEmpty()
    }

    /** a -1 marks a provider that would not tell us a size; anything else is the declared length */
    private fun sizesOf(intent: Intent, count: Int): List<Long?> {
        val raw = intent.getLongArrayExtra(EXTRA_SIZES) ?: return List(count) { null }
        return List(count) { index -> raw.getOrNull(index)?.takeIf { it >= 0 } }
    }

    companion object {
        private const val ACTION_TRANSFER = "com.teilen.app.action.TRANSFER"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_FDS = "fds"
        private const val EXTRA_NAMES = "names"
        private const val EXTRA_MIMES = "mimes"
        private const val EXTRA_SIZES = "sizes"
        private const val EXTRA_TTL = "ttl"
        private const val DEFAULT_TTL_SECONDS = 1800L

        /**
         * Hands a share to the background. [fds] is null for a text-only share; when present it is
         * paired with [names], [mimes] and [sizes] (a null size means the provider did not say).
         */
        fun start(
            context: Context,
            text: String?,
            fds: ArrayList<ParcelFileDescriptor>?,
            names: ArrayList<String>?,
            mimes: ArrayList<String>?,
            sizes: LongArray?,
            ttlSeconds: Long
        ) {
            val intent = Intent(context, TransferService::class.java).apply {
                action = ACTION_TRANSFER
                if (!text.isNullOrBlank()) {
                    putExtra(EXTRA_TEXT, text)
                }
                if (fds != null && fds.isNotEmpty()) {
                    putParcelableArrayListExtra(EXTRA_FDS, fds)
                    putStringArrayListExtra(EXTRA_NAMES, names)
                    putStringArrayListExtra(EXTRA_MIMES, mimes)
                    putExtra(EXTRA_SIZES, sizes ?: LongArray(fds.size) { -1L })
                }
                putExtra(EXTRA_TTL, ttlSeconds)
            }
            context.startForegroundService(intent)
        }
    }
}

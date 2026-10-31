package com.teilen.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.net.wifi.WifiManager
import android.system.Os
import android.system.OsConstants
import android.widget.Toast
import androidx.core.content.IntentCompat
import java.io.FileInputStream

/**
 * Headless background share upload service.
 * Runs silently away from any UI so it survives the user staying inside WhatsApp / host app.
 * Displays a quiet, low-priority progress notification for ongoing transfers, dismisses
 * it on completion, and shows crisp completion Toasts.
 */
class TransferService : Service() {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false
    @Volatile private var currentRoute: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        TeilenNotifications.ensureTransferChannel(this)
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "teilen:transfer_wakelock")?.apply {
            setReferenceCounted(false)
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "teilen:transfer_wifilock")?.apply {
            setReferenceCounted(false)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelled = true
            done(getString(R.string.share_cancelled), ok = false)
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_TRANSFER) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        wakeLock?.acquire(60 * 60 * 1000L)
        wifiLock?.acquire()

        val text = intent.getStringExtra(EXTRA_TEXT)
        val uris = urisOf(intent)
        val fds = fdsOf(intent).toMutableList()
        val names = intent.getStringArrayListExtra(EXTRA_NAMES)?.toMutableList() ?: mutableListOf()
        val mimes = intent.getStringArrayListExtra(EXTRA_MIMES)?.toMutableList() ?: mutableListOf()
        val sizes = sizesOf(intent, fds.size).toMutableList()
        val ttl = intent.getLongExtra(EXTRA_TTL, DEFAULT_TTL_SECONDS)

        // If FDs weren't opened in activity, resolve them now via granted URI permissions
        if (fds.isEmpty() && uris.isNotEmpty()) {
            for (uri in uris) {
                val descriptor = runCatching { contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
                if (descriptor != null) {
                    fds.add(descriptor)
                    names.add(TeilenApi.displayName(this, uri))
                    mimes.add(contentResolver.getType(uri) ?: "application/octet-stream")
                    sizes.add(TeilenApi.declaredSize(this, uri))
                }
            }
        }

        val initialTitle = if (names.isNotEmpty()) {
            "Sending ${names.first()} to Teilen..."
        } else if (!text.isNullOrBlank()) {
            "Sending to Teilen..."
        } else {
            getString(R.string.sending)
        }
        currentRoute = getString(R.string.upload_via_relay)
        startInForeground(workingNotification(initialTitle, currentRoute))

        // Ensure authenticated silently before proceeding
        Session.ensureAuthenticated(this, onSuccess = {
            transfer(text, fds, names, mimes, sizes, ttl)
        }, onError = { err ->
            val msg = if (err.contains("network", ignoreCase = true) || err.contains("connect", ignoreCase = true)) {
                "Failed to share: check internet connection"
            } else {
                "Failed to share: $err"
            }
            done(msg, ok = false, isText = !text.isNullOrBlank(), names = names, text = text)
        })

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
        // When files are present, upload the actual files without sending fallback captions as text cards
        sendFiles(fds, names, mimes, sizes, ttl)
    }

    private fun sendText(text: String, ttl: Long) {
        if (text.isBlank()) {
            done(getString(R.string.share_empty), ok = false, isText = true, text = text)
            return
        }
        val activeSession = Session.activeSessionId(this)
        val isUrl = text.startsWith("http://", ignoreCase = true) ||
                text.startsWith("https://", ignoreCase = true) ||
                android.util.Patterns.WEB_URL.matcher(text.trim()).matches()
        val itemType = if (isUrl) "LINK" else "TEXT"

        TeilenApi.send(Server.get(this), Session.token(this), text, ttl, type = itemType, sessionId = activeSession) { ok, message ->
            done(if (ok) getString(R.string.share_sent) else message, ok = ok, isText = true, text = text)
        }
    }

    private fun sendFiles(
        fds: List<ParcelFileDescriptor>,
        names: List<String>,
        mimes: List<String>,
        sizes: List<Long?>,
        ttl: Long
    ) {
        if (fds.size == 1) {
            val name = names.getOrNull(0) ?: "file"
            val mime = mimes.getOrNull(0) ?: "application/octet-stream"
            val size = sizes.getOrNull(0) ?: -1L
            val isDirectLanOnly = size > TeilenApi.MAX_UPLOAD_BYTES

            currentRoute = if (isDirectLanOnly) "⚡ Direct LAN Only (0 Server Bytes)" else getString(R.string.upload_via_lan)
            manager().notify(
                TeilenNotifications.TRANSFER_ID,
                workingNotification("Sending $name to Teilen...", currentRoute)
            )
            val engine = LanTransferEngine(this)
            engine.attempt(
                payload = LanTransferEngine.Payload(name, mime, size) { inputOf(fds[0]) },
                onProgress = { written, total ->
                    if (!cancelled) {
                        onProgress(1, 1, written, total, name)
                    }
                }
            ) { sent ->
                if (cancelled) return@attempt
                if (sent) {
                    done(getString(R.string.share_sent), ok = true, isText = false, names = names)
                } else if (isDirectLanOnly) {
                    done("Files over 30 MB transfer directly device-to-device. Ensure receiving device is open on same Wi-Fi.", ok = false, isText = false, names = names)
                } else {
                    currentRoute = getString(R.string.upload_via_relay)
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
        currentRoute = getString(R.string.upload_via_relay)
        val count = fds.size
        val activeSession = Session.activeSessionId(this)
        val firstName = names.firstOrNull() ?: "file"
        manager().notify(
            TeilenNotifications.TRANSFER_ID,
            workingNotification("Sending $firstName to Teilen...", currentRoute)
        )
        TeilenApi.uploadAllFds(
            baseUrl = Server.get(this),
            token = Session.token(this),
            fds = fds,
            names = names,
            mimes = mimes,
            sizes = sizes,
            ttlSeconds = ttl,
            sessionId = activeSession,
            onProgress = { index, _, written, total ->
                val curName = names.getOrNull(index - 1) ?: firstName
                if (!cancelled) onProgress(index, count, written, total, curName)
            },
            onDone = { outcome ->
                if (cancelled) return@uploadAllFds
                val message = when {
                    outcome.failed == 0 && outcome.sent == 1 -> getString(R.string.share_sent)
                    outcome.failed == 0 -> getString(R.string.share_files_sent, outcome.sent)
                    else -> outcome.lastError ?: getString(R.string.file_failed, "")
                }
                done(message, ok = (outcome.failed == 0), isText = false, names = names)
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

    private fun workingNotification(text: String, route: String? = currentRoute): Notification {
        val builder = TeilenNotifications.transferBuilder(this)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .setContentIntent(openApp())
            .addAction(R.drawable.ic_notification, getString(R.string.cancel), cancelIntent())
        if (!route.isNullOrBlank()) {
            builder.setSubText(route)
        }
        return builder.build()
    }

    private fun onProgress(index: Int, count: Int, written: Long, total: Long?, fileName: String? = null) {
        if (cancelled) return
        val builder = TeilenNotifications.transferBuilder(this)
            .setContentTitle(getString(R.string.app_name))
            .setOngoing(true)
            .setContentIntent(openApp())
            .addAction(R.drawable.ic_notification, getString(R.string.cancel), cancelIntent())
        if (!currentRoute.isNullOrBlank()) {
            builder.setSubText(currentRoute)
        }
        val namePrefix = if (!fileName.isNullOrBlank()) "Sending $fileName to Teilen..." else "Sending to Teilen..."
        if (total != null && total > 0) {
            val percent = ((written * 100) / total).toInt().coerceIn(0, 100)
            builder
                .setContentText("$namePrefix (${TeilenApi.humanSize(written)} / ${TeilenApi.humanSize(total)})")
                .setProgress(100, percent, false)
        } else {
            builder
                .setContentText(
                    if (count > 1) "Sending $count files to Teilen..." else namePrefix
                )
                .setProgress(0, 0, true)
        }
        manager().notify(TeilenNotifications.TRANSFER_ID, builder.build())
    }

    private fun cancelIntent(): PendingIntent = PendingIntent.getService(
        this,
        10,
        Intent(this, TransferService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Completion handler: dismisses notification, triggers toast, and broadcasts update */
    private fun done(
        message: String,
        ok: Boolean,
        isText: Boolean = false,
        names: List<String> = emptyList(),
        text: String? = null
    ) {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }

        // Dismiss the progress notification completely on finish
        stopForeground(STOP_FOREGROUND_REMOVE)
        manager().cancel(TeilenNotifications.TRANSFER_ID)

        // Toast message on Main thread
        main.post {
            val toastText = if (ok) {
                if (isText || (!text.isNullOrBlank() && names.isEmpty())) {
                    val isUrl = text?.let {
                        it.startsWith("http://", ignoreCase = true) ||
                        it.startsWith("https://", ignoreCase = true) ||
                        android.util.Patterns.WEB_URL.matcher(it.trim()).matches()
                    } ?: false
                    if (isUrl) getString(R.string.toast_shared_link) else getString(R.string.toast_shared_text)
                } else {
                    val filename = names.firstOrNull() ?: "file"
                    if (names.size > 1) getString(R.string.toast_shared_files_multi, names.size) else getString(R.string.toast_shared_file, filename)
                }
            } else {
                if (message.contains("network", ignoreCase = true) ||
                    message.contains("connect", ignoreCase = true) ||
                    message.contains("resolve", ignoreCase = true)) {
                    getString(R.string.toast_share_failed_offline)
                } else if (message.startsWith("Failed to share")) {
                    message
                } else {
                    "Failed to share: $message"
                }
            }
            Toast.makeText(applicationContext, toastText, Toast.LENGTH_SHORT).show()
        }

        // Broadcast local intent so if MainActivity is open in the background, its Feed updates
        val broadcastIntent = Intent(ACTION_ITEM_SHARED_HEADLESS).apply {
            setPackage(packageName)
            putExtra("ok", ok)
        }
        sendBroadcast(broadcastIntent)

        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
    }

    private fun manager() = getSystemService(NotificationManager::class.java)

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // ------------------------------------------------------------------ intent plumbing

    private fun urisOf(intent: Intent): List<Uri> {
        val list = IntentCompat.getParcelableArrayListExtra(intent, EXTRA_URIS, Uri::class.java)
        return list?.filterNotNull().orEmpty()
    }

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
        const val ACTION_CANCEL = "com.teilen.app.action.CANCEL_TRANSFER"
        const val ACTION_TRANSFER = "com.teilen.app.action.TRANSFER"
        const val ACTION_ITEM_SHARED_HEADLESS = "com.teilen.app.action.ITEM_SHARED_HEADLESS"
        const val EXTRA_TEXT = "text"
        const val EXTRA_URIS = "uris"
        const val EXTRA_FDS = "fds"
        const val EXTRA_NAMES = "names"
        const val EXTRA_MIMES = "mimes"
        const val EXTRA_SIZES = "sizes"
        const val EXTRA_TTL = "ttl"
        const val DEFAULT_TTL_SECONDS = 3600L

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
            ttlSeconds: Long,
            uris: ArrayList<Uri>? = null
        ) {
            val intent = Intent(context, TransferService::class.java).apply {
                action = ACTION_TRANSFER
                if (!text.isNullOrBlank()) {
                    putExtra(EXTRA_TEXT, text)
                }
                if (uris != null && uris.isNotEmpty()) {
                    putParcelableArrayListExtra(EXTRA_URIS, uris)
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

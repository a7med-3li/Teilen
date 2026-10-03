package com.teilen.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Notices when you copy something and *offers* to send it. Never sends by itself.
 *
 * Why a notification is the mechanism: since Android 10 an app may only read the clipboard
 * while it has focus (or while it is the keyboard), so a background service cannot grab what
 * you just copied. A notification action brings up a transparent activity, that focus is
 * what makes the read legal, and the tap is the explicit "yes, send this one".
 */
class ClipboardWatchService : Service() {

    private lateinit var clipboard: ClipboardManager
    private var listening = false

    private val listener = ClipboardManager.OnPrimaryClipChangedListener { offer() }

    private sealed interface State {
        data object Idle : State
        data class Offer(val preview: String?) : State
    }

    override fun onCreate() {
        super.onCreate()
        TeilenNotifications.ensureChannel(this)
        clipboard = getSystemService(ClipboardManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESET) {
            publish(State.Idle)
            return START_STICKY
        }
        startInForeground(State.Idle)
        if (!listening) {
            clipboard.addPrimaryClipChangedListener(listener)
            listening = true
        }
        offer()
        return START_STICKY
    }

    override fun onDestroy() {
        if (listening) {
            clipboard.removePrimaryClipChangedListener(listener)
        }
        listening = false
        lastCopy = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** the clip description is not access-restricted, so the "you copied something" signal is reliable */
    private fun offer() {
        val description = clipboard.primaryClipDescription
        val isText = description?.hasMimeType("text/*") == true
        if (!isText) {
            publish(State.Idle)
            return
        }
        publish(State.Offer(peekText()))
    }

    /** best effort only: in the background this is usually blocked, which is why the tap exists */
    private fun peekText(): String? = runCatching {
        clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.text
            ?.toString()
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun startInForeground(state: State) {
        val notification = build(state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(TeilenNotifications.WATCH_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(TeilenNotifications.WATCH_ID, notification)
        }
    }

    private fun publish(state: State) {
        getSystemService(android.app.NotificationManager::class.java)
            .notify(TeilenNotifications.WATCH_ID, build(state))
    }

    private fun build(state: State): Notification {
        val builder = TeilenNotifications.builder(this)
        return when (state) {
            State.Idle -> builder
                .setContentTitle(getString(R.string.watch_idle_title))
                .setContentText(getString(R.string.watch_idle_text))
                .setContentIntent(openApp())
                .addAction(R.drawable.ic_notification, getString(R.string.stop_watching), openApp(stop = true))
                .build()

            is State.Offer -> builder
                .setContentTitle(getString(R.string.watch_offer_title))
                .setContentText(state.preview?.take(80) ?: getString(R.string.watch_offer_text))
                .setContentIntent(send())
                .addAction(R.drawable.ic_notification, getString(R.string.send), send())
                .addAction(R.drawable.ic_notification, getString(R.string.stop_watching), openApp(stop = true))
                .build()
        }
    }

    private fun send(): PendingIntent = PendingIntent.getActivity(
        this,
        1,
        Intent(this, SendClipboardActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun openApp(stop: Boolean = false): PendingIntent = PendingIntent.getActivity(
        this,
        if (stop) 3 else 2,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_STOP_WATCHING, stop),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    companion object {
        const val ACTION_RESET = "com.teilen.app.RESET_WATCHING"

        /**
         * What the background service could not read, kept for the process that gets focus.
         * Null after a process restart, in which case the clipboard itself is used.
         */
        @Volatile
        var lastCopy: String? = null

        fun start(context: Context) {
            val intent = Intent(context, ClipboardWatchService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ClipboardWatchService::class.java))
        }

        /** back to "waiting" after something was sent */
        fun reset(context: Context) {
            context.startService(Intent(context, ClipboardWatchService::class.java).setAction(ACTION_RESET))
        }

        fun isWatching(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WATCHING, false)

        fun setWatching(context: Context, watching: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_WATCHING, watching)
                .apply()
        }

        private const val PREFS = "teilen"
        private const val KEY_WATCHING = "watching_clipboard"
    }
}

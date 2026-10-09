package com.teilen.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object TeilenNotifications {

    const val WATCH_ID = 1
    const val TRANSFER_ID = 2

    private const val CHANNEL_ID = "teilen.clipboard"
    private const val TRANSFER_CHANNEL_ID = "teilen.transfers"

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_clipboard),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.channel_clipboard_description)
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** progress for background shares, so a large file has somewhere to report to */
    fun ensureTransferChannel(context: Context) {
        val channel = NotificationChannel(
            TRANSFER_CHANNEL_ID,
            context.getString(R.string.channel_transfer),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.channel_transfer_description)
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun builder(context: Context): Notification.Builder =
        Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)

    fun transferBuilder(context: Context): Notification.Builder =
        Notification.Builder(context, TRANSFER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
}

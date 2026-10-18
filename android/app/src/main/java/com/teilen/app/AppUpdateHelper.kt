package com.teilen.app

import android.app.Activity
import android.util.Log
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallState
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Smart Flexible + Immediate In-App Update Manager using the Google Play In-App Updates API.
 * Handles update availability queries, immediate flow for critical/stale releases,
 * flexible background downloading, persistent completion prompts, and safe fallbacks for sideloaded builds.
 */
class AppUpdateHelper(
    private val activity: Activity,
    private val onUpdateDownloaded: () -> Unit
) {

    companion object {
        private const val TAG = "AppUpdateHelper"
        const val REQ_APP_UPDATE = 8001
        private const val CRITICAL_STALENESS_DAYS = 7
        private const val CRITICAL_UPDATE_PRIORITY = 4
    }

    private val appUpdateManager: AppUpdateManager = AppUpdateManagerFactory.create(activity)
    private var isListenerRegistered = false

    private val installStateListener = InstallStateUpdatedListener { state: InstallState ->
        when (state.installStatus()) {
            InstallStatus.DOWNLOADED -> {
                Log.d(TAG, "In-app update downloaded and ready to install")
                onUpdateDownloaded()
            }
            InstallStatus.DOWNLOADING -> {
                val bytes = state.bytesDownloaded()
                val total = state.totalBytesToDownload()
                Log.d(TAG, "Downloading update: $bytes / $total")
            }
            InstallStatus.FAILED -> {
                Log.w(TAG, "Update download failed with code: ${state.installErrorCode()}")
            }
            else -> {}
        }
    }

    /**
     * Starts listening for install state changes (call in onCreate / onStart).
     */
    fun start() {
        if (!isListenerRegistered) {
            try {
                appUpdateManager.registerListener(installStateListener)
                isListenerRegistered = true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register install state listener: ${e.message}")
            }
        }
    }

    /**
     * Stops listening for install state changes (call in onDestroy).
     */
    fun stop() {
        if (isListenerRegistered) {
            try {
                appUpdateManager.unregisterListener(installStateListener)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister install state listener: ${e.message}")
            }
            isListenerRegistered = false
        }
    }

    /**
     * Queries Google Play silently or manually for available updates.
     * Triggers IMMEDIATE if staleness >= 7 days or priority >= 4; otherwise FLEXIBLE.
     */
    fun checkForUpdates(
        isManual: Boolean = false,
        onCheckResult: ((isLatest: Boolean) -> Unit)? = null
    ) {
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { info: AppUpdateInfo ->
                val availability = info.updateAvailability()
                if (availability == UpdateAvailability.UPDATE_AVAILABLE) {
                    val stalenessDays = info.clientVersionStalenessDays() ?: 0
                    val priority = info.updatePriority()
                    val isImmediate = (stalenessDays >= CRITICAL_STALENESS_DAYS || priority >= CRITICAL_UPDATE_PRIORITY) &&
                            info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)

                    val updateType = if (isImmediate) {
                        AppUpdateType.IMMEDIATE
                    } else if (info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
                        AppUpdateType.FLEXIBLE
                    } else if (info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                        AppUpdateType.IMMEDIATE
                    } else {
                        null
                    }

                    if (updateType != null) {
                        try {
                            appUpdateManager.startUpdateFlowForResult(
                                info,
                                activity,
                                AppUpdateOptions.defaultOptions(updateType),
                                REQ_APP_UPDATE
                            )
                            onCheckResult?.invoke(false)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to start update flow: ${e.message}")
                            onCheckResult?.invoke(true)
                        }
                    } else {
                        onCheckResult?.invoke(true)
                    }
                } else if (availability == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                    try {
                        appUpdateManager.startUpdateFlowForResult(
                            info,
                            activity,
                            AppUpdateOptions.defaultOptions(AppUpdateType.IMMEDIATE),
                            REQ_APP_UPDATE
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to resume developer-triggered update: ${e.message}")
                    }
                    onCheckResult?.invoke(false)
                } else {
                    if (isManual) {
                        onCheckResult?.invoke(true)
                    }
                }
            }
            .addOnFailureListener { e ->
                // Sideloaded or non-Play install: log non-fatal message and gracefully fall back
                Log.w(TAG, "Play In-App Update check failed (non-fatal / sideloaded): ${e.message}")
                if (isManual) {
                    onCheckResult?.invoke(true)
                }
            }
    }

    /**
     * Checks in onResume if an update is already downloaded or an immediate update was in progress.
     */
    fun onResumeCheck() {
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { info: AppUpdateInfo ->
                if (info.installStatus() == InstallStatus.DOWNLOADED) {
                    onUpdateDownloaded()
                } else if (info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                    try {
                        appUpdateManager.startUpdateFlowForResult(
                            info,
                            activity,
                            AppUpdateOptions.defaultOptions(AppUpdateType.IMMEDIATE),
                            REQ_APP_UPDATE
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to resume immediate update on resume: ${e.message}")
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "onResume update check failed: ${e.message}")
            }
    }

    /**
     * Installs downloaded flexible update and restarts the app.
     */
    fun completeUpdate() {
        try {
            appUpdateManager.completeUpdate()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to complete update: ${e.message}")
        }
    }

    /**
     * Attempts to trigger immediate update flow; falls back to onFallback if not available.
     */
    fun triggerImmediateUpdate(onFallback: () -> Unit) {
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { info: AppUpdateInfo ->
                if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                    info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
                ) {
                    try {
                        appUpdateManager.startUpdateFlowForResult(
                            info,
                            activity,
                            AppUpdateOptions.defaultOptions(AppUpdateType.IMMEDIATE),
                            REQ_APP_UPDATE
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to launch immediate update: ${e.message}")
                        onFallback()
                    }
                } else {
                    onFallback()
                }
            }
            .addOnFailureListener {
                onFallback()
            }
    }
}

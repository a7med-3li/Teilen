package com.teilen.app

import android.content.Context

/**
 * Where the backend lives. Kept in SharedPreferences because the address changes:
 * 10.0.2.2 from the emulator, a LAN IP from a real phone, localhost over adb reverse.
 */
object Server {

    const val DEFAULT_URL = "http://192.168.100.4:8081"

    private const val PREFS = "teilen"
    private const val KEY_URL = "server_url"

    fun get(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_URL, DEFAULT_URL)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_URL

    fun set(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URL, normalize(url))
            .apply()
    }

    fun normalize(url: String): String = url.trim().trimEnd('/')

    /** label for the time-to-live picker: seconds -> "30 min" */
    fun label(seconds: Long): String = when {
        seconds % 3600 == 0L -> "${seconds / 3600} h"
        seconds % 60 == 0L -> "${seconds / 60} min"
        else -> "$seconds s"
    }
}

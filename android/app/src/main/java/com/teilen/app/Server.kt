package com.teilen.app

import android.content.Context

/**
 * Where the backend lives. The app ships with a URL and uses it unless the user deliberately
 * sets another one: a saved address only counts when it is a real choice, never when it is a
 * leftover from a previous install — otherwise a stale IP would silently win over the server the
 * app was built with, and pairing and sending would aim at a dead address.
 *
 * Kept in SharedPreferences because the address can change: a LAN IP from a real phone, or
 * 10.0.2.2 from the emulator. Anything the user types into the backend card and saves counts.
 */
object Server {

    /** the server the app was built against; what the backend card shows by default */
    const val DEFAULT_URL = "https://teileneg.duckdns.org/"

    private const val PREFS = "teilen"
    private const val KEY_URL = "server_url"
    private const val KEY_EXPLICIT = "server_url_explicit"

    /**
     * The built-in URL, unless the user saved their own. A stored value only wins when the last
     * [set] deliberately chose it — i.e. it differs from the current default — so an old address
     * from a previous build is ignored and the app always starts on the URL it was built with.
     */
    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_URL, null)?.takeIf { it.isNotBlank() }
        return if (stored != null && prefs.getBoolean(KEY_EXPLICIT, false)) stored else DEFAULT_URL
    }

    /**
     * Saves a URL and marks whether it is the user's own choice. Saving the default itself, or a
     * leftover from an older install, does not count as a choice — the built-in URL stays in force.
     */
    fun set(context: Context, url: String) {
        val normalized = normalize(url)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URL, normalized)
            .putBoolean(KEY_EXPLICIT, normalized.isNotEmpty() && normalized != normalize(DEFAULT_URL))
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
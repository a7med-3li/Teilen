package com.teilen.app

import android.content.Context

/**
 * Who this installation is: the bearer token the backend checks on every call, plus what to show
 * about it. Lives in the same SharedPreferences file as the server address, in plain text.
 *
 * The token is a credential and this is an ordinary unencrypted app sandbox, so anything with
 * access to the phone's data can read it — the same as every other app on the phone, and the same
 * trade-off this project makes everywhere else.
 */
object Session {

    private const val PREFS = "teilen"
    private const val KEY_TOKEN = "device_token"
    private const val KEY_PHONE = "phone"
    private const val KEY_DEVICE = "device_name"

    /** null when this device is not paired with any account */
    fun token(context: Context): String? = read(context, KEY_TOKEN)

    /**
     * The number of the account this device last paired with. Kept across sign-out so the number
     * can be offered back as a suggestion — it is the user's own, and not a credential.
     */
    fun phone(context: Context): String? = read(context, KEY_PHONE)

    /** what the account knows this device as */
    fun deviceName(context: Context): String? = read(context, KEY_DEVICE)

    /** the only thing that decides whether this device can talk to the backend */
    fun isPaired(context: Context): Boolean = token(context) != null

    fun save(context: Context, token: String, phone: String?, deviceName: String?) {
        prefs(context).edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_PHONE, phone)
            .putString(KEY_DEVICE, deviceName)
            .apply()
    }

    /** forget the credential; the number stays, so signing back in is one tap less typing */
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_TOKEN).apply()
    }

    private fun read(context: Context, key: String): String? =
        prefs(context).getString(key, null)?.takeIf { it.isNotBlank() }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

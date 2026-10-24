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
    private const val KEY_USER_ID = "user_id"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_CLIENT_FINGERPRINT = "client_fingerprint"
    private const val KEY_PHONE = "phone"
    private const val KEY_DEVICE = "device_name"
    private const val KEY_ACTIVE_SESSION = "active_session_id"

    /** null when this device is not paired with any account */
    fun token(context: Context): String? = read(context, KEY_TOKEN)

    fun userId(context: Context): String? = read(context, KEY_USER_ID)

    fun setUserId(context: Context, id: String?) {
        if (id.isNullOrBlank()) prefs(context).edit().remove(KEY_USER_ID).apply()
        else prefs(context).edit().putString(KEY_USER_ID, id).apply()
    }

    fun deviceId(context: Context): String? = read(context, KEY_DEVICE_ID)

    fun setDeviceId(context: Context, id: String?) {
        if (id.isNullOrBlank()) prefs(context).edit().remove(KEY_DEVICE_ID).apply()
        else prefs(context).edit().putString(KEY_DEVICE_ID, id).apply()
    }

    fun clientFingerprint(context: Context): String {
        var fp = read(context, KEY_CLIENT_FINGERPRINT)
        if (fp.isNullOrBlank()) {
            fp = java.util.UUID.randomUUID().toString()
            prefs(context).edit().putString(KEY_CLIENT_FINGERPRINT, fp).apply()
        }
        return fp
    }

    /**
     * The number of the account this device last paired with. Kept across sign-out so the number
     * can be offered back as a suggestion — it is the user's own, and not a credential.
     */
    fun phone(context: Context): String? = read(context, KEY_PHONE)

    /** what the account knows this device as */
    fun deviceName(context: Context): String? = read(context, KEY_DEVICE)

    /** currently selected active session ID, or null for the user's default Personal session */
    fun activeSessionId(context: Context): String? = read(context, KEY_ACTIVE_SESSION)

    fun setActiveSessionId(context: Context, sessionId: String?) {
        if (sessionId.isNullOrBlank()) {
            prefs(context).edit().remove(KEY_ACTIVE_SESSION).apply()
        } else {
            prefs(context).edit().putString(KEY_ACTIVE_SESSION, sessionId).apply()
        }
    }

    fun getSessionKey(context: Context, sessionId: String): ByteArray? {
        val b64 = prefs(context).getString("session_key_$sessionId", null) ?: return null
        return try { android.util.Base64.decode(b64, android.util.Base64.NO_WRAP) } catch (e: Exception) { null }
    }

    fun setSessionKey(context: Context, sessionId: String, key: ByteArray) {
        val b64 = android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)
        prefs(context).edit().putString("session_key_$sessionId", b64).apply()
    }

    fun getAllSessionKeys(context: Context): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val all = prefs(context).all
        for ((k, v) in all) {
            if (k.startsWith("session_key_") && v is String) {
                result[k.removePrefix("session_key_")] = v
            }
        }
        return result
    }

    fun setAllSessionKeys(context: Context, keys: Map<String, String>) {
        val editor = prefs(context).edit()
        for ((sessionId, b64Key) in keys) {
            editor.putString("session_key_$sessionId", b64Key)
        }
        editor.apply()
    }

    /** the only thing that decides whether this device can talk to the backend */
    fun isPaired(context: Context): Boolean = token(context) != null

    fun save(context: Context, token: String, phone: String?, deviceName: String?, userId: String? = null, deviceId: String? = null) {
        val editor = prefs(context).edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_PHONE, phone)
            .putString(KEY_DEVICE, deviceName)
            .remove(KEY_ACTIVE_SESSION)
        if (!userId.isNullOrBlank()) editor.putString(KEY_USER_ID, userId)
        if (!deviceId.isNullOrBlank()) editor.putString(KEY_DEVICE_ID, deviceId)
        editor.apply()
    }

    /** forget the credential; the number stays, so signing back in is one tap less typing */
    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_DEVICE_ID)
            .remove(KEY_ACTIVE_SESSION)
            .apply()
    }

    private fun read(context: Context, key: String): String? =
        prefs(context).getString(key, null)?.takeIf { it.isNotBlank() }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

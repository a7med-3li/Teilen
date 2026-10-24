package com.teilen.app

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The entire backend client: pairing, text POSTs and multipart uploads, no HTTP library.
 *
 * Every call takes the bearer token explicitly, because the caller is the only thing that knows
 * which account it is acting for. A null token means "not paired", and the server will say so.
 */
object TeilenApi {

    /** server-side cap; checked here too so an oversized file never leaves the phone */
    const val MAX_UPLOAD_BYTES = 30L * 1024 * 1024

    /**
     * What a 401 comes back as, and the one string callers compare against: it means the token is
     * no longer paired with anything, which is the app's cue to forget it and show the account card
     * again rather than to sit there looking broken.
     */
    const val UNPAIRED_MESSAGE = "not paired any more — pair this phone again in the app"

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val BUFFER = 64 * 1024

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    // ------------------------------------------------------------------ accounts

    /** a credential came back, and with it who it belongs to */
    data class SignedIn(
        val token: String,
        val phone: String?,
        val deviceName: String?,
        val deviceId: String?
    )

    /** a code to show, and the secret to collect its token with */
    data class PairingOffer(
        val deviceCode: String,
        val userCode: String,
        val pollIntervalMillis: Long
    )

    /** one poll of a pairing this device is waiting on */
    sealed class PairingPoll {
        /** nobody has answered yet: not an error, so not an error string */
        data object Waiting : PairingPoll()

        /** @param keyPackage the approver's sealed account-key bundle, or null if none was left */
        data class Paired(val signedIn: SignedIn, val keyPackage: String?) : PairingPoll()

        /** denied, expired, or never issued — the message says which */
        data class Failed(val message: String) : PairingPoll()
    }

    /**
     * What a scanned code is asking for, so the prompt can name it before anyone taps allow.
     *
     * @param publicKey the newcomer's single-use X25519 key, to seal the account key to, or null
     */
    data class PairingPreview(
        val deviceName: String,
        val deviceType: String,
        val platform: String?,
        val publicKey: String?,
        val mode: String = "NEWCOMER_REQUEST"
    )

    data class HostInviteOffer(
        val userCode: String,
        val deviceCode: String,
        val qrSvgUrl: String,
        val expiresInSeconds: Long
    )

    data class ClaimInviteResult(
        val ok: Boolean,
        val signedIn: SignedIn? = null,
        val encryptedKeyBundle: String? = null,
        val primaryUserId: String? = null,
        val isPrimaryHost: Boolean = true,
        val error: String? = null
    )

    data class SettlementResult(
        val settled: Boolean,
        val mergeKeyPackage: String? = null,
        val token: String? = null,
        val username: String? = null
    )

    /**
     * Claims a phone number and pairs this device with it.
     *
     * This is the one call that hands out a credential to a stranger, and it proves nothing about
     * the number: whoever asks first owns it. Adding an OTP would mean checking it right here.
     *
     * @param onResult always on the main thread; [CreateResult.Taken] is the caller's cue to switch
     *                to pairing instead of typing a different number
     */
    fun createAccount(
        baseUrl: String,
        phone: String,
        password: String,
        displayName: String,
        deviceName: String,
        onResult: (CreateResult) -> Unit
    ) {
        Thread {
            val result = try {
                val reply = postBlocking(
                    baseUrl,
                    "/api/auth/account",
                    token = null,
                    JSONObject()
                        .put("username", phone)
                        .put("phone", phone)
                        .put("password", password)
                        .put("displayName", displayName)
                        .put("deviceName", deviceName)
                        .put("deviceType", "PHONE")
                )
                when {
                    reply.ok -> CreateResult.Created(signedInFrom(reply))
                    reply.status == 409 -> CreateResult.Taken(reply.message)
                    else -> CreateResult.Failed(reply.message)
                }
            } catch (e: IOException) {
                CreateResult.Failed(unreachable(baseUrl, e))
            } catch (e: Exception) {
                CreateResult.Failed(e.message ?: e.javaClass.simpleName)
            }
            main.post { onResult(result) }
        }.apply { name = "teilen-auth" }.start()
    }

    /** what claiming a number came to */
    sealed class CreateResult {
        data class Created(val signedIn: SignedIn) : CreateResult()

        /** the number already has an account, so this device has to be paired or logged into */
        data class Taken(val message: String) : CreateResult()

        data class Failed(val message: String) : CreateResult()
    }

    /**
     * Logs into an existing account with phone number and password.
     */
    fun login(
        baseUrl: String,
        phone: String,
        password: String,
        deviceName: String,
        onResult: (LoginResult) -> Unit
    ) {
        Thread {
            val result = try {
                val reply = postBlocking(
                    baseUrl,
                    "/api/auth/login",
                    token = null,
                    JSONObject()
                        .put("username", phone)
                        .put("phone", phone)
                        .put("password", password)
                        .put("deviceName", deviceName)
                        .put("deviceType", "PHONE")
                )
                when {
                    reply.ok -> LoginResult.Success(signedInFrom(reply))
                    reply.status == 401 -> LoginResult.InvalidCredentials(reply.message)
                    else -> LoginResult.Failed(reply.message)
                }
            } catch (e: IOException) {
                LoginResult.Failed(unreachable(baseUrl, e))
            } catch (e: Exception) {
                LoginResult.Failed(e.message ?: e.javaClass.simpleName)
            }
            main.post { onResult(result) }
        }.apply { name = "teilen-login" }.start()
    }

    sealed class LoginResult {
        data class Success(val signedIn: SignedIn) : LoginResult()
        data class InvalidCredentials(val message: String) : LoginResult()
        data class Failed(val message: String) : LoginResult()
    }

    /**
     * Step one of pairing from this phone: ask for a code, then keep polling for a token.
     *
     * @param publicKey this device's single-use X25519 public key, base64, so the approving device
     *                  can seal the account key to it; null to pair without a key exchange
     * @param onOffer the code to show, always on the main thread
     * @param onPolled the outcome of every poll, always on the main thread
     */
    fun pairThisDevice(
        baseUrl: String,
        deviceName: String,
        publicKey: String?,
        onOffer: (PairingOffer?) -> Unit,
        onPolled: (PairingPoll) -> Unit
    ) {
        Thread {
            val offer = try {
                val body = JSONObject()
                    .put("deviceName", deviceName)
                    .put("deviceType", "PHONE")
                    .put("platform", "Android ${android.os.Build.VERSION.RELEASE}")
                if (publicKey != null) {
                    body.put("publicKey", publicKey)
                }
                val reply = postBlocking(baseUrl, "/api/auth/device/code", token = null, body)
                if (!reply.ok) {
                    null
                } else {
                    PairingOffer(
                        deviceCode = reply.str("deviceCode").orEmpty(),
                        userCode = reply.str("userCode").orEmpty(),
                        pollIntervalMillis = reply.num("pollIntervalSeconds", 3L) * 1000L
                    )
                }
            } catch (e: Exception) {
                null
            }
            main.post {
                onOffer(offer)
                if (offer != null && offer.deviceCode.isNotBlank()) {
                    pollForToken(baseUrl, offer, onPolled)
                }
            }
        }.apply { name = "teilen-pair" }.start()
    }

    /** step three of pairing, once per poll interval until it settles */
    private fun pollForToken(baseUrl: String, offer: PairingOffer, onPolled: (PairingPoll) -> Unit) {
        Thread {
            val poll = try {
                val reply = postBlocking(
                    baseUrl,
                    "/api/auth/device/token",
                    token = null,
                    JSONObject().put("deviceCode", offer.deviceCode)
                )
                when {
                    reply.status == 202 -> PairingPoll.Waiting
                    reply.ok -> PairingPoll.Paired(signedInFrom(reply), reply.str("keyPackage"))
                    else -> PairingPoll.Failed(reply.message)
                }
            } catch (e: IOException) {
                PairingPoll.Failed(unreachable(baseUrl, e))
            } catch (e: Exception) {
                PairingPoll.Failed(e.message ?: e.javaClass.simpleName)
            }
            main.post {
                onPolled(poll)
                // 202 means keep waiting; anything else has settled and the caller stops the loop
                if (poll is PairingPoll.Waiting) {
                    main.postDelayed({ pollForToken(baseUrl, offer, onPolled) }, offer.pollIntervalMillis)
                }
            }
        }.apply { name = "teilen-pair" }.start()
    }

    /**
     * What a code in a deep link is asking for, so the dialog can name the newcomer before anyone
     * taps allow. Open on purpose — the code *is* the credential, so holding it is as good as being
     * that device — and it only ever answers with a name and a kind.
     *
     * @param onResult null when the code is unknown, already spent, or ran out
     */
    fun describePairing(baseUrl: String, userCode: String, onResult: (PairingPreview?) -> Unit) {
        Thread {
            val preview = try {
                val reply = getBlocking(
                    baseUrl,
                    "/api/auth/device/pending?code=" + URLEncoder.encode(userCode, "UTF-8")
                )
                if (reply.ok) {
                    PairingPreview(
                        deviceName = reply.str("deviceName") ?: "a device",
                        deviceType = reply.str("deviceType") ?: "WEB",
                        platform = reply.str("platform"),
                        publicKey = reply.str("publicKey"),
                        mode = reply.str("mode") ?: "NEWCOMER_REQUEST"
                    )
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
            main.post { onResult(preview) }
        }.apply { name = "teilen-auth" }.start()
    }

    /**
     * Step two, from a device that is already paired: allow or turn down a code someone showed.
     *
     * @param keyPackage on approve, the account key sealed for the newcomer's offered public key;
     *                   null when there was no key to exchange and pairing proceeds without one
     * @param onResult (ok, message), always on the main thread
     */
    fun decidePairing(
        baseUrl: String,
        token: String,
        userCode: String,
        allow: Boolean,
        keyPackage: String?,
        mergeKeyPackage: String? = null,
        onResult: (ok: Boolean, message: String) -> Unit
    ) {
        Thread {
            val result = try {
                val body = JSONObject().put("userCode", userCode)
                if (allow && keyPackage != null) {
                    body.put("keyPackage", keyPackage)
                }
                if (allow && mergeKeyPackage != null) {
                    body.put("mergeKeyPackage", mergeKeyPackage)
                }
                val reply = postBlocking(
                    baseUrl,
                    "/api/auth/device/" + if (allow) "approve" else "deny",
                    token,
                    body
                )
                if (reply.ok) {
                    true to (reply.str("deviceName") ?: if (allow) "paired" else "turned down")
                } else {
                    false to reply.message
                }
            } catch (e: IOException) {
                false to unreachable(baseUrl, e)
            } catch (e: Exception) {
                false to (e.message ?: e.javaClass.simpleName)
            }
            main.post { onResult(result.first, result.second) }
        }.apply { name = "teilen-auth" }.start()
    }

    /**
     * Creates a Host Invite so other devices can join this vault without logging in.
     */
    fun createHostInvite(
        baseUrl: String,
        token: String,
        userCode: String?,
        encryptedKeyBundle: String,
        deviceName: String,
        platform: String = "Android ${android.os.Build.VERSION.RELEASE}",
        onResult: (HostInviteOffer?) -> Unit
    ) {
        Thread {
            val offer = try {
                val body = JSONObject()
                    .put("encryptedKeyBundle", encryptedKeyBundle)
                    .put("deviceName", deviceName)
                    .put("platform", platform)
                if (userCode != null) {
                    body.put("userCode", userCode)
                }
                val reply = postBlocking(baseUrl, "/api/auth/device/host-invite", token, body)
                if (reply.ok) {
                    HostInviteOffer(
                        userCode = reply.str("userCode").orEmpty(),
                        deviceCode = reply.str("deviceCode").orEmpty(),
                        qrSvgUrl = reply.str("qrSvgUrl").orEmpty(),
                        expiresInSeconds = reply.num("expiresInSeconds", 300L)
                    )
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
            main.post { onResult(offer) }
        }.apply { name = "teilen-host-invite" }.start()
    }

    /**
     * Claims a Host Invite without any existing account or login.
     */
    fun claimHostInvite(
        baseUrl: String,
        userCode: String,
        deviceName: String,
        deviceType: String = "PHONE",
        devicePublicKey: String? = null,
        platform: String = "Android ${android.os.Build.VERSION.RELEASE}",
        mergeKeyPackage: String? = null,
        scannerToken: String? = null,
        onResult: (ClaimInviteResult) -> Unit
    ) {
        Thread {
            val result = try {
                val body = JSONObject()
                    .put("userCode", userCode)
                    .put("deviceName", deviceName)
                    .put("deviceType", deviceType)
                    .put("platform", platform)
                if (devicePublicKey != null) {
                    body.put("devicePublicKey", devicePublicKey)
                }
                if (mergeKeyPackage != null) {
                    body.put("mergeKeyPackage", mergeKeyPackage)
                }
                if (scannerToken != null) {
                    body.put("scannerToken", scannerToken)
                }
                val reply = postBlocking(baseUrl, "/api/auth/device/claim-invite", token = scannerToken, body)
                if (reply.ok) {
                    val token = reply.str("token").orEmpty()
                    val signedIn = SignedIn(
                        token = token,
                        phone = reply.str("username") ?: reply.str("deviceName").orEmpty(),
                        deviceName = reply.str("deviceName").orEmpty(),
                        deviceId = reply.str("deviceId")
                    )
                    ClaimInviteResult(
                        ok = true,
                        signedIn = signedIn,
                        encryptedKeyBundle = reply.str("encryptedKeyBundle"),
                        primaryUserId = reply.str("primaryUserId"),
                        isPrimaryHost = reply.bool("isPrimaryHost", true)
                    )
                } else {
                    ClaimInviteResult(ok = false, error = reply.message)
                }
            } catch (e: IOException) {
                ClaimInviteResult(ok = false, error = unreachable(baseUrl, e))
            } catch (e: Exception) {
                ClaimInviteResult(ok = false, error = e.message ?: e.javaClass.simpleName)
            }
            main.post { onResult(result) }
        }.apply { name = "teilen-claim-invite" }.start()
    }

    /**
     * Polls until a pairing request settles (is approved/claimed or fails).
     */
    fun pollForSettlement(
        baseUrl: String,
        deviceCode: String,
        pollIntervalMillis: Long = 3000L,
        onSettled: (SettlementResult) -> Unit
    ) {
        Thread {
            val reply = try {
                postBlocking(
                    baseUrl,
                    "/api/auth/device/token",
                    token = null,
                    JSONObject().put("deviceCode", deviceCode)
                )
            } catch (e: Exception) {
                null
            }
            main.post {
                if (reply != null && reply.status != 202 && reply.ok) {
                    onSettled(
                        SettlementResult(
                            settled = true,
                            mergeKeyPackage = reply.str("mergeKeyPackage"),
                            token = reply.str("token"),
                            username = reply.str("user.username") ?: reply.str("username")
                        )
                    )
                } else if (reply != null && reply.status == 202) {
                    main.postDelayed({
                        pollForSettlement(baseUrl, deviceCode, pollIntervalMillis, onSettled)
                    }, pollIntervalMillis)
                } else {
                    onSettled(SettlementResult(settled = false))
                }
            }
        }.apply { name = "teilen-poll-settle" }.start()
    }

    /**
     * Revokes all other active sessions/devices for the current account.
     */
    fun revokeOtherDevices(baseUrl: String, token: String?, onDone: (ok: Boolean, message: String) -> Unit) {
        Thread {
            val result = try {
                val connection = open(baseUrl, "/api/devices/others", token, "DELETE")
                try {
                    val status = connection.responseCode
                    if (status in 200..299 || status == 204) {
                        true to "All other devices signed out"
                    } else if (status == 401) {
                        false to UNPAIRED_MESSAGE
                    } else {
                        false to "server said $status"
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                false to (e.message ?: "network error")
            }
            main.post { onDone(result.first, result.second) }
        }.apply { name = "teilen-revoke" }.start()
    }

    data class DeviceInfo(
        val id: String,
        val name: String,
        val deviceType: String,
        val current: Boolean,
        val lastSeenAt: String?
    )

    /**
     * Lists all registered devices for the current account.
     */
    fun getDevices(
        baseUrl: String,
        token: String?,
        onResult: (List<DeviceInfo>?) -> Unit
    ) {
        if (token == null) {
            main.post { onResult(null) }
            return
        }
        Thread {
            val list = try {
                val connection = open(baseUrl, "/api/devices", token, "GET")
                try {
                    val code = connection.responseCode
                    if (code in 200..299) {
                        val body = connection.inputStream.bufferedReader().use { it.readText() }
                        val array = org.json.JSONArray(body)
                        val result = mutableListOf<DeviceInfo>()
                        for (i in 0 until array.length()) {
                            val obj = array.getJSONObject(i)
                            result.add(
                                DeviceInfo(
                                    id = obj.optString("id"),
                                    name = obj.optString("name", "Device"),
                                    deviceType = obj.optString("deviceType", "WEB"),
                                    current = obj.optBoolean("current", false),
                                    lastSeenAt = if (obj.has("lastSeenAt") && !obj.isNull("lastSeenAt")) obj.getString("lastSeenAt") else null
                                )
                            )
                        }
                        result
                    } else {
                        null
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                null
            }
            main.post { onResult(list) }
        }.apply { name = "teilen-devices" }.start()
    }

    // ------------------------------------------------------------------ multi-sessions

    data class SessionInfo(
        val id: String,
        val name: String,
        val isPersonal: Boolean,
        val role: String?,
        val memberCount: Long,
        val encryptedKey: String? = null
    ) {
        companion object {
            fun parse(json: JSONObject): SessionInfo? {
                val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
                return SessionInfo(
                    id = id,
                    name = json.optString("name").ifBlank { "Personal" },
                    isPersonal = json.optBoolean("isPersonal", false),
                    role = json.optString("role").takeIf { it.isNotBlank() },
                    memberCount = json.optLong("memberCount", 1L),
                    encryptedKey = json.optString("encryptedKey").takeIf { it.isNotBlank() }
                )
            }
        }
    }

    fun fetchSessions(baseUrl: String, token: String?, onResult: (List<SessionInfo>?, String?) -> Unit) {
        Thread {
            val result = try {
                val connection = open(baseUrl, "/api/sessions", token, "GET")
                val (status, text) = try {
                    readBody(connection)
                } finally {
                    connection.disconnect()
                }
                when {
                    status in 200..299 -> {
                        val array = runCatching { JSONArray(text) }.getOrNull() ?: JSONArray()
                        val list = (0 until array.length()).mapNotNull { i ->
                            array.optJSONObject(i)?.let { SessionInfo.parse(it) }
                        }
                        list to null
                    }
                    status == 401 -> null to UNPAIRED_MESSAGE
                    else -> null to "server said $status"
                }
            } catch (e: IOException) {
                null to unreachable(baseUrl, e)
            } catch (e: Exception) {
                null to (e.message ?: e.javaClass.simpleName)
            }
            main.post { onResult(result.first, result.second) }
        }.apply { this.name = "teilen-sessions" }.start()
    }

    fun createSession(
        baseUrl: String,
        token: String?,
        name: String,
        encryptedKey: String? = null,
        onResult: (SessionInfo?, String?) -> Unit
    ) {
        Thread {
            val result = try {
                val body = JSONObject().put("name", name)
                if (encryptedKey != null) {
                    body.put("encryptedKey", encryptedKey)
                }
                val reply = postBlocking(baseUrl, "/api/sessions", token, body)
                if (reply.ok && reply.body != null) {
                    SessionInfo.parse(reply.body) to null
                } else {
                    null to reply.message
                }
            } catch (e: IOException) {
                null to unreachable(baseUrl, e)
            } catch (e: Exception) {
                null to (e.message ?: e.javaClass.simpleName)
            }
            main.post { onResult(result.first, result.second) }
        }.apply { this.name = "teilen-sessions" }.start()
    }

    fun generateSessionInvite(
        baseUrl: String,
        token: String?,
        sessionId: String,
        onResult: (code: String?, error: String?) -> Unit
    ) {
        Thread {
            val result = try {
                val reply = postBlocking(baseUrl, "/api/sessions/$sessionId/invite", token, JSONObject())
                if (reply.ok && reply.body != null) {
                    reply.str("inviteCode") to null
                } else {
                    null to reply.message
                }
            } catch (e: IOException) {
                null to unreachable(baseUrl, e)
            } catch (e: Exception) {
                null to (e.message ?: e.javaClass.simpleName)
            }
            main.post { onResult(result.first, result.second) }
        }.apply { this.name = "teilen-sessions" }.start()
    }

    fun joinSession(
        baseUrl: String,
        token: String?,
        inviteCode: String,
        encryptedKey: String? = null,
        onResult: (SessionInfo?, String?) -> Unit
    ) {
        Thread {
            val result = try {
                val body = JSONObject().put("inviteCode", inviteCode)
                if (encryptedKey != null) {
                    body.put("encryptedKey", encryptedKey)
                }
                val reply = postBlocking(baseUrl, "/api/sessions/join", token, body)
                if (reply.ok && reply.body != null) {
                    SessionInfo.parse(reply.body) to null
                } else {
                    null to reply.message
                }
            } catch (e: IOException) {
                null to unreachable(baseUrl, e)
            } catch (e: Exception) {
                null to (e.message ?: e.javaClass.simpleName)
            }
            main.post { onResult(result.first, result.second) }
        }.apply { this.name = "teilen-sessions" }.start()
    }

    fun deleteSession(
        baseUrl: String,
        token: String?,
        sessionId: String,
        onDone: (ok: Boolean, message: String) -> Unit
    ) {
        Thread {
            val result = try {
                val connection = open(baseUrl, "/api/sessions/$sessionId", token, "DELETE")
                try {
                    val status = connection.responseCode
                    if (status in 200..299 || status == 204) {
                        true to "Session deleted"
                    } else if (status == 401) {
                        false to UNPAIRED_MESSAGE
                    } else {
                        false to "server said $status"
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                false to (e.message ?: "network error")
            }
            main.post { onDone(result.first, result.second) }
        }.apply { name = "teilen-sessions" }.start()
    }

    fun leaveSession(
        baseUrl: String,
        token: String?,
        sessionId: String,
        userId: String,
        onDone: (ok: Boolean, message: String) -> Unit
    ) {
        Thread {
            val result = try {
                val connection = open(baseUrl, "/api/sessions/$sessionId/members/$userId", token, "DELETE")
                try {
                    val status = connection.responseCode
                    if (status in 200..299 || status == 204) {
                        true to "Left session"
                    } else if (status == 401) {
                        false to UNPAIRED_MESSAGE
                    } else {
                        false to "server said $status"
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                false to (e.message ?: "network error")
            }
            main.post { onDone(result.first, result.second) }
        }.apply { name = "teilen-sessions" }.start()
    }

    // ------------------------------------------------------------------ items

    /**
     * The whole live feed for the token's account, newest first. One request, no cursor: the list
     * is bounded by the time-to-live, so re-reading it costs less than keeping a delta in step.
     *
     * @param onResult (items, error), always on the main thread; exactly one of the two is set
     */
    fun fetchItems(
        baseUrl: String,
        token: String?,
        sessionId: String? = null,
        onResult: (List<FeedItem>?, String?) -> Unit
    ) {
        Thread {
            val result = try {
                val path = if (!sessionId.isNullOrBlank()) {
                    "/api/items?sessionId=" + URLEncoder.encode(sessionId, "UTF-8")
                } else {
                    "/api/items"
                }
                val connection = open(baseUrl, path, token, "GET")
                val (status, text) = try {
                    readBody(connection)
                } finally {
                    connection.disconnect()
                }
                when {
                    status in 200..299 -> parseItems(text) to null
                    status == 401 -> null to UNPAIRED_MESSAGE
                    else -> null to "server said $status"
                }
            } catch (e: IOException) {
                null to unreachable(baseUrl, e)
            } catch (e: Exception) {
                null to (e.message ?: e.javaClass.simpleName)
            }
            val items = result.first
            val error = result.second
            main.post { onResult(items, error) }
        }.apply { name = "teilen-feed" }.start()
    }

    /** the feed is a bare array, so it cannot go through [Reply]'s object parser */
    private fun parseItems(text: String): List<FeedItem> {
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { FeedItem.parse(it) }
        }
    }

    /**
     * Deletes a feed item by ID.
     */
    fun deleteItem(baseUrl: String, token: String?, id: String, onDone: (ok: Boolean, message: String) -> Unit) {
        Thread {
            val result = try {
                val connection = open(baseUrl, "/api/items/$id", token, "DELETE")
                try {
                    val status = connection.responseCode
                    if (status in 200..299 || status == 204) {
                        true to "deleted"
                    } else if (status == 401) {
                        false to UNPAIRED_MESSAGE
                    } else {
                        false to "server said $status"
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                false to (e.message ?: "network error")
            }
            main.post { onDone(result.first, result.second) }
        }.apply { name = "teilen-delete" }.start()
    }

    /**
     * Extends a feed item's TTL (+1 hour) via POST /api/v1/items/:id/extend.
     */
    fun extendItem(baseUrl: String, token: String?, id: String, onDone: (ok: Boolean, message: String) -> Unit) {
        Thread {
            val result = try {
                val connection = open(baseUrl, "/api/v1/items/$id/extend", token, "POST")
                try {
                    connection.doOutput = true
                    connection.outputStream.close()
                    val status = connection.responseCode
                    if (status in 200..299) {
                        true to "extended"
                    } else if (status == 401) {
                        false to UNPAIRED_MESSAGE
                    } else {
                        false to "server said $status"
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                false to (e.message ?: "network error")
            }
            main.post { onDone(result.first, result.second) }
        }.apply { name = "teilen-extend" }.start()
    }

    /**
     * Sets a custom TTL for a feed item via PUT /api/v1/items/:id/ttl.
     */
    fun updateItemTtl(
        baseUrl: String,
        token: String?,
        id: String,
        ttlSeconds: Long,
        onDone: (ok: Boolean, message: String) -> Unit
    ) {
        Thread {
            val result = try {
                val connection = open(baseUrl, "/api/v1/items/$id/ttl", token, "PUT")
                try {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    val body = JSONObject().put("ttlSeconds", ttlSeconds)
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    val status = connection.responseCode
                    if (status in 200..299) {
                        true to "updated"
                    } else if (status == 401) {
                        false to UNPAIRED_MESSAGE
                    } else {
                        false to "server said $status"
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                false to (e.message ?: "network error")
            }
            main.post { onDone(result.first, result.second) }
        }.apply { name = "teilen-ttl" }.start()
    }

    /** @param onResult (ok, message), always on the main thread */
    fun send(
        baseUrl: String,
        token: String?,
        content: String,
        ttlSeconds: Long,
        type: String? = null,
        sessionId: String? = null,
        onResult: (ok: Boolean, message: String) -> Unit
    ) {
        Thread {
            val result = try {
                val url = URL("${Server.normalize(baseUrl)}/api/items")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    authorize(token)
                }
                try {
                    val body = JSONObject()
                        .put("content", content)
                        .put("ttlSeconds", ttlSeconds)
                    if (type != null) {
                        body.put("type", type)
                    }
                    if (!sessionId.isNullOrBlank()) {
                        body.put("sessionId", sessionId)
                    }
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    finish(connection, "sent")
                } finally {
                    connection.disconnect()
                }
            } catch (e: IOException) {
                false to unreachable(baseUrl, e)
            } catch (e: Exception) {
                false to (e.message ?: e.javaClass.simpleName)
            }
            main.post { onResult(result.first, result.second) }
        }.apply { name = "teilen-upload" }.start()
    }

    /** how a batch of uploads ended */
    data class BatchOutcome(val sent: Int, val total: Int, val lastError: String?) {
        val failed: Int get() = total - sent
    }

    /**
     * One file per request, so a multi-select share becomes several feed items and each keeps
     * its own expiry. Runs the list in order on one worker at a time — parallel uploads over a
     * phone connection just make everything slower.
     *
     * @param onProgress index (1-based), file count, bytes written, total bytes if known
     * @param onDone always on the main thread, once, after the last file
     */
    fun uploadAll(
        context: Context,
        baseUrl: String,
        token: String?,
        uris: List<Uri>,
        ttlSeconds: Long,
        sessionId: String? = null,
        onProgress: (index: Int, count: Int, written: Long, total: Long?) -> Unit,
        onDone: (BatchOutcome) -> Unit
    ) {
        if (uris.isEmpty()) {
            main.post { onDone(BatchOutcome(0, 0, null)) }
            return
        }
        var sent = 0
        var lastError: String? = null

        fun next(index: Int) {
            if (index >= uris.size) {
                main.post { onDone(BatchOutcome(sent, uris.size, lastError)) }
                return
            }
            val uri = uris[index]
            val name = displayName(context, uri)
            upload(
                context = context,
                baseUrl = baseUrl,
                token = token,
                uri = uri,
                displayName = name,
                ttlSeconds = ttlSeconds,
                sessionId = sessionId,
                onProgress = { written, total -> onProgress(index + 1, uris.size, written, total) },
                onResult = { ok, message ->
                    if (ok) {
                        sent++
                    } else {
                        lastError = "$name — $message"
                    }
                    next(index + 1)
                }
            )
        }

        next(0)
    }

    /**
     * @param onProgress bytes written so far and the total if the provider told us, else null
     * @param onResult (ok, message), always on the main thread
     */
    fun upload(
        context: Context,
        baseUrl: String,
        token: String?,
        uri: Uri,
        displayName: String,
        ttlSeconds: Long,
        sessionId: String? = null,
        onProgress: (written: Long, total: Long?) -> Unit,
        onResult: (ok: Boolean, message: String) -> Unit
    ) {
        Thread {
            val result = try {
                uploadBlocking(baseUrl, token, sourceOf(context, uri, displayName), ttlSeconds, sessionId) { written, total ->
                    main.post { onProgress(written, total) }
                }
            } catch (e: TooLargeException) {
                false to e.message!!
            } catch (e: FileNotFoundException) {
                false to "cannot open $displayName"
            } catch (e: IOException) {
                false to "upload failed (${e.message ?: "network error"})"
            } catch (e: Exception) {
                false to (e.message ?: e.javaClass.simpleName)
            }
            main.post { onResult(result.first, result.second) }
        }.apply { name = "teilen-file" }.start()
    }

    /**
     * The background-service counterpart of [uploadAll]: the descriptors were opened by the
     * sharing activity because a content:// read grant dies with that activity's task, while a
     * descriptor can be handed on and outlive it.
     *
     * @param onProgress index (1-based), file count, bytes written, total bytes if known
     * @param onDone always on the main thread, once, after the last file
     */
    fun uploadAllFds(
        baseUrl: String,
        token: String?,
        fds: List<ParcelFileDescriptor>,
        names: List<String>,
        mimes: List<String>,
        sizes: List<Long?>,
        ttlSeconds: Long,
        sessionId: String? = null,
        onProgress: (index: Int, count: Int, written: Long, total: Long?) -> Unit,
        onDone: (BatchOutcome) -> Unit
    ) {
        if (fds.isEmpty()) {
            main.post { onDone(BatchOutcome(0, 0, null)) }
            return
        }
        var sent = 0
        var lastError: String? = null

        fun next(index: Int) {
            if (index >= fds.size) {
                main.post { onDone(BatchOutcome(sent, fds.size, lastError)) }
                return
            }
            val name = names.getOrElse(index) { "shared-file" }
            uploadFd(
                baseUrl = baseUrl,
                token = token,
                fd = fds[index],
                displayName = name,
                mime = mimes.getOrElse(index) { "application/octet-stream" },
                declaredSize = sizes.getOrNull(index),
                ttlSeconds = ttlSeconds,
                sessionId = sessionId,
                onProgress = { written, total -> onProgress(index + 1, fds.size, written, total) },
                onResult = { ok, message ->
                    if (ok) {
                        sent++
                    } else {
                        lastError = "$name — $message"
                    }
                    next(index + 1)
                }
            )
        }

        next(0)
    }

    private fun uploadFd(
        baseUrl: String,
        token: String?,
        fd: ParcelFileDescriptor,
        displayName: String,
        mime: String,
        declaredSize: Long?,
        ttlSeconds: Long,
        sessionId: String? = null,
        onProgress: (written: Long, total: Long?) -> Unit,
        onResult: (ok: Boolean, message: String) -> Unit
    ) {
        Thread {
            val result = try {
                uploadBlocking(
                    baseUrl = baseUrl,
                    token = token,
                    source = Source(displayName, mime, declaredSize) {
                        ParcelFileDescriptor.AutoCloseInputStream(fd)
                    },
                    ttlSeconds = ttlSeconds,
                    sessionId = sessionId,
                    onProgress = { written, total ->
                        main.post { onProgress(written, total) }
                    }
                )
            } catch (e: TooLargeException) {
                false to e.message!!
            } catch (e: IOException) {
                false to "upload failed (${e.message ?: "network error"})"
            } catch (e: Exception) {
                false to (e.message ?: e.javaClass.simpleName)
            } finally {
                // AutoCloseInputStream usually got there first; closing twice is a no-op
                runCatching { fd.close() }
            }
            main.post { onResult(result.first, result.second) }
        }.apply { name = "teilen-file" }.start()
    }

    /** one upload's bytes, however they are opened: a content:// URI or a passed descriptor */
    private class Source(
        val name: String,
        val mime: String,
        val declaredSize: Long?,
        val open: () -> InputStream
    )

    private fun sourceOf(context: Context, uri: Uri, displayName: String): Source {
        val resolver = context.contentResolver
        return Source(
            name = displayName,
            mime = resolver.getType(uri) ?: "application/octet-stream",
            declaredSize = declaredSize(context, uri),
            open = { resolver.openInputStream(uri) ?: throw FileNotFoundException("no stream for $displayName") }
        )
    }

    private fun uploadBlocking(
        baseUrl: String,
        token: String?,
        source: Source,
        ttlSeconds: Long,
        sessionId: String? = null,
        onProgress: (Long, Long?) -> Unit
    ): Pair<Boolean, String> {
        val declared = source.declaredSize

        // reject early on an honest length, but never trust it: the stream is counted too
        if (declared != null && declared > MAX_UPLOAD_BYTES) {
            throw TooLargeException("${source.name} is ${humanSize(declared)} — the limit is 30 MB")
        }

        val boundary = "teilen-${System.currentTimeMillis()}"
        val url = URL("${Server.normalize(baseUrl)}/api/items")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            // a 30 MB upload over a slow link needs more than the usual read timeout
            readTimeout = 120_000
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
            authorize(token)
        }

        try {
            connection.outputStream.buffered().use { out ->
                writePart(out, boundary, "ttlSeconds", ttlSeconds.toString())
                if (!sessionId.isNullOrBlank()) {
                    writePart(out, boundary, "sessionId", sessionId)
                }
                writeFileHeader(out, boundary, source.name, source.mime)

                source.open().use { input ->
                    copy(input, out) { written ->
                        if (written > MAX_UPLOAD_BYTES) {
                            throw TooLargeException("${source.name} is over the 30 MB limit")
                        }
                        onProgress(written, declared)
                    }
                }

                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            return finish(connection, "sent")
        } finally {
            connection.disconnect()
        }
    }

    /** counts as it copies, so the cap is enforced on the bytes and not on a header */
    private fun copy(input: InputStream, out: OutputStream, onBytes: (Long) -> Unit) {
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) {
                break
            }
            total += read
            if (total > MAX_UPLOAD_BYTES) {
                throw TooLargeException("file is over the 30 MB limit")
            }
            out.write(buffer, 0, read)
            onBytes(total)
        }
    }

    private fun writePart(out: OutputStream, boundary: String, name: String, value: String) {
        out.write("--$boundary\r\n".toByteArray())
        out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
        out.write(value.toByteArray(Charsets.UTF_8))
        out.write("\r\n".toByteArray())
    }

    private fun writeFileHeader(out: OutputStream, boundary: String, fileName: String, mime: String) {
        // strip quotes and newlines so the header cannot be broken by a hostile file name
        val safeName = fileName.replace("\"", "_").replace("\r", "_").replace("\n", "_")
        out.write("--$boundary\r\n".toByteArray())
        out.write("Content-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\n".toByteArray())
        out.write("Content-Type: $mime\r\n\r\n".toByteArray())
    }

    private fun finish(connection: HttpURLConnection, okMessage: String): Pair<Boolean, String> {
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.let { BufferedInputStream(it) }?.bufferedReader()?.use { it.readText() }.orEmpty()
        return if (status in 200..299) {
            true to okMessage
        } else if (status == 401) {
            // the backend's way of saying this device is no longer paired with anything
            false to UNPAIRED_MESSAGE
        } else {
            false to "server said $status: ${extractMessage(text)}"
        }
    }

    /** the one line of configuration that makes a call authenticated */
    private fun HttpURLConnection.authorize(token: String?) {
        if (token != null) {
            setRequestProperty("Authorization", "Bearer $token")
        }
    }

    private fun unreachable(baseUrl: String, e: Exception): String =
        "cannot reach ${Server.normalize(baseUrl)} (${e.message ?: "network error"})"

    // ------------------------------------------------ small JSON calls, no model layer

    /** a status code and, if the body was JSON, the body — that is all these calls need */
    private class Reply(val status: Int, val body: JSONObject?) {

        val ok: Boolean get() = status in 200..299

        fun str(name: String): String? = body?.optString(name)?.takeIf { it.isNotBlank() }

        fun num(name: String, fallback: Long): Long = when (val value = body?.opt(name)) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: fallback
            else -> fallback
        }

        fun bool(name: String, fallback: Boolean = true): Boolean = when (val value = body?.opt(name)) {
            is Boolean -> value
            is String -> value.toBooleanStrictOrNull() ?: fallback
            else -> fallback
        }

        /** the server's own wording, which beats a status code every time */
        val message: String get() = str("message") ?: "server said $status"
    }

    private fun postBlocking(baseUrl: String, path: String, token: String?, body: JSONObject): Reply {
        val connection = open(baseUrl, path, token, "POST")
        return try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            readReply(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun getBlocking(baseUrl: String, path: String): Reply {
        val connection = open(baseUrl, path, token = null, "GET")
        return try {
            readReply(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(baseUrl: String, path: String, token: String?, method: String): HttpURLConnection {
        val connection = (URL("${Server.normalize(baseUrl)}$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            setRequestProperty("Accept", "application/json")
            authorize(token)
        }
        return connection
    }

    /** the status code and the body as text, which is all a caller needs to pick a parser */
    private fun readBody(connection: HttpURLConnection): Pair<Int, String> {
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.let { BufferedInputStream(it) }?.bufferedReader()?.use { it.readText() }.orEmpty()
        return status to text
    }

    private fun readReply(connection: HttpURLConnection): Reply {
        val (status, text) = readBody(connection)
        val json = if (text.isBlank()) null else runCatching { JSONObject(text) }.getOrNull()
        return Reply(status, json)
    }

    /** every credential comes back in the same shape, whether it was minted or claimed */
    private fun signedInFrom(reply: Reply): SignedIn {
        val user = reply.body?.optJSONObject("user")
        val username = user?.optString("username")?.takeIf { it.isNotBlank() }
            ?: user?.optString("phone")?.takeIf { it.isNotBlank() }
        return SignedIn(
            token = reply.str("token").orEmpty(),
            phone = username,
            deviceName = reply.str("deviceName"),
            deviceId = reply.str("deviceId")
        )
    }

    fun declaredSize(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
    }.getOrNull()

    fun displayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
    }.getOrNull() ?: uri.lastPathSegment ?: "shared-file"

    fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    private fun extractMessage(body: String): String = try {
        JSONObject(body).optString("message").ifBlank { body.take(160) }
    } catch (e: Exception) {
        body.take(160)
    }

    data class VersionInfo(
        val minAndroidVersionCode: Int,
        val latestAndroidVersionCode: Int
    )

    fun fetchVersion(baseUrl: String, onResult: (VersionInfo?) -> Unit) {
        Thread {
            val info = try {
                val reply = getBlocking(baseUrl, "/api/version")
                if (reply.ok && reply.body != null) {
                    VersionInfo(
                        minAndroidVersionCode = reply.body.optInt("minAndroidVersionCode", 0),
                        latestAndroidVersionCode = reply.body.optInt("latestAndroidVersionCode", 0)
                    )
                } else null
            } catch (_: Exception) {
                null
            }
            main.post { onResult(info) }
        }.apply { name = "teilen-version" }.start()
    }

    private class TooLargeException(message: String) : IOException(message)
}
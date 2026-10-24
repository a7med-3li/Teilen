package com.teilen.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder

/**
 * The account management component: supports logging in with username & password,
 * creating an account, newcomer pairing (QR display + 1-tap copy), approver pairing
 * (manual code entry + in-app QR scanner trigger), and managing the active session.
 */
class AccountCard(
    private val activity: Activity,
    private val onScanQr: () -> Unit = {},
    private val onReplayGuide: () -> Unit = {},
    private val onDevicesCountChanged: (Int) -> Unit = {},
    private val onPaired: () -> Unit
) {

    enum class AuthMode { LOGIN, REGISTER }

    private val createBox: View = activity.findViewById(R.id.account_create)
    private val pairBox: View = activity.findViewById(R.id.account_pair)
    private val who: TextView = activity.findViewById(R.id.account_who)
    private val phone: EditText = activity.findViewById(R.id.phone) // Username input field
    private val password: EditText = activity.findViewById(R.id.password)
    private val displayName: EditText = activity.findViewById(R.id.display_name)
    private val createButton: Button = activity.findViewById(R.id.create_account)
    private val pairStart: Button = activity.findViewById(R.id.account_pair_start)
    private val signOut: Button = activity.findViewById(R.id.sign_out)
    private val pairCode: TextView = activity.findViewById(R.id.pair_code)
    private val pairQr: ImageView = activity.findViewById(R.id.pair_qr)
    private val pairQrSpinner: ProgressBar = activity.findViewById(R.id.pair_qr_spinner)
    private val btnCopyPairCode: Button = activity.findViewById(R.id.btn_copy_pair_code)
    private val pairStatus: TextView = activity.findViewById(R.id.pair_status)
    private val pairCancel: Button = activity.findViewById(R.id.pair_cancel)
    private val status: TextView = activity.findViewById(R.id.account_status)

    private val authSwitchLogin: Button = activity.findViewById(R.id.auth_switch_login)
    private val authSwitchRegister: Button = activity.findViewById(R.id.auth_switch_register)
    private val authIntro: TextView = activity.findViewById(R.id.auth_intro)

    // Permanent Companion Card views
    private val cardWebCompanion: View = activity.findViewById(R.id.card_web_companion)
    private val txtAccountWebUrl: TextView = activity.findViewById(R.id.txt_account_web_url)
    private val btnAccountCopyUrl: Button = activity.findViewById(R.id.btn_account_copy_url)
    private val btnAccountShareLink: Button = activity.findViewById(R.id.btn_account_share_link)

    // Replay Guide & Connected Devices
    private val cardReplayGuide: View = activity.findViewById(R.id.card_replay_guide)
    private val cardDevices: View = activity.findViewById(R.id.card_devices)
    private val containerDevicesList: LinearLayout = activity.findViewById(R.id.container_devices_list)
    private val btnAccountRevokeOthers: Button = activity.findViewById(R.id.btn_account_revoke_others)

    // Expandable Advanced Auth Toggle
    private val btnToggleAdvancedAuth: View = activity.findViewById(R.id.btn_toggle_advanced_auth)
    private val txtAdvancedChevron: TextView = activity.findViewById(R.id.txt_advanced_chevron)
    private val layoutAdvancedAuth: View = activity.findViewById(R.id.layout_advanced_auth)
    private var advancedAuthExpanded = false

    // Approver Mode Views
    private val cardPairDevice: View = activity.findViewById(R.id.card_pair_device)
    private val inputApproveCode: EditText = activity.findViewById(R.id.input_approve_code)
    private val btnScanQr: Button = activity.findViewById(R.id.btn_scan_qr)
    private val btnApproveCode: Button = activity.findViewById(R.id.btn_approve_code)
    private val approvePairStatus: TextView = activity.findViewById(R.id.approve_pair_status)
    private val btnRevokeOthers: Button = activity.findViewById(R.id.btn_revoke_others)

    private var mode = AuthMode.LOGIN

    /** the name the backend will list this device under in the account's device list */
    private val deviceName: String =
        listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL)
            .filterNot { it.isNullOrBlank() }
            .joinToString(" ")
            .ifBlank { "this phone" }

    private var busy = false
    private var pairing = false

    init {
        authSwitchLogin.setOnClickListener { setAuthMode(AuthMode.LOGIN) }
        authSwitchRegister.setOnClickListener { setAuthMode(AuthMode.REGISTER) }
        createButton.setOnClickListener { submitAuth() }
        pairStart.setOnClickListener { startPairing() }
        pairCancel.setOnClickListener { pairing = false; render() }
        signOut.setOnClickListener { signOut() }

        // Approver pairing listeners
        btnScanQr.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onScanQr()
        }
        btnApproveCode.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            handleApproveCode(inputApproveCode.text.toString())
        }
        btnRevokeOthers.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            val token = Session.token(activity) ?: return@setOnClickListener
            AlertDialog.Builder(activity)
                .setTitle("Sign out all other devices?")
                .setMessage("All other sessions and devices paired with this account will be immediately revoked.")
                .setPositiveButton("Sign Out Others") { _, _ ->
                    TeilenApi.revokeOtherDevices(Server.get(activity), token) { ok, msg ->
                        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                        loadDevices()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        btnAccountRevokeOthers.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            val token = Session.token(activity) ?: return@setOnClickListener
            AlertDialog.Builder(activity)
                .setTitle("Sign out all other devices?")
                .setMessage("All other sessions and devices paired with this account will be immediately revoked.")
                .setPositiveButton("Sign Out Others") { _, _ ->
                    TeilenApi.revokeOtherDevices(Server.get(activity), token) { ok, msg ->
                        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                        loadDevices()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        txtAccountWebUrl.text = Server.get(activity)

        btnAccountCopyUrl.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            val cm = activity.getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("Teilen Web URL", Server.get(activity)))
            Toast.makeText(activity, "Web URL copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        btnAccountShareLink.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, Server.get(activity))
            }
            activity.startActivity(Intent.createChooser(shareIntent, activity.getString(R.string.btn_share_link)))
        }

        cardReplayGuide.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onReplayGuide()
        }

        btnToggleAdvancedAuth.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            advancedAuthExpanded = !advancedAuthExpanded
            layoutAdvancedAuth.visibility = if (advancedAuthExpanded) View.VISIBLE else View.GONE
            createBox.visibility = if (advancedAuthExpanded) View.VISIBLE else View.GONE
            txtAdvancedChevron.text = if (advancedAuthExpanded) "▴" else "▾"
        }

        updateModeUI()
    }

    private fun setAuthMode(newMode: AuthMode) {
        mode = newMode
        updateModeUI()
        status.visibility = View.GONE
    }

    private fun updateModeUI() {
        val isLogin = mode == AuthMode.LOGIN
        authSwitchLogin.setBackgroundResource(
            if (isLogin) R.drawable.bg_segmented_selected else android.R.color.transparent
        )
        authSwitchLogin.setTextColor(
            activity.getColor(if (isLogin) R.color.accent else R.color.text_secondary)
        )

        authSwitchRegister.setBackgroundResource(
            if (!isLogin) R.drawable.bg_segmented_selected else android.R.color.transparent
        )
        authSwitchRegister.setTextColor(
            activity.getColor(if (!isLogin) R.color.accent else R.color.text_secondary)
        )

        authIntro.text = activity.getString(
            if (isLogin) R.string.account_mode_login_hint else R.string.account_mode_register_hint
        )
        displayName.visibility = if (isLogin) View.GONE else View.VISIBLE
        createButton.text = activity.getString(
            if (isLogin) R.string.login_button else R.string.create_account
        )
    }

    /** shows whichever shape matches the session, and re-enables the rest of the app to match */
    fun render() {
        val paired = Session.isPaired(activity)
        when {
            pairing -> renderPairing()
            paired -> renderPaired()
            else -> renderSignedOut()
        }
        onPaired()
    }

    // ------------------------------------------------------------- signed out

    private fun renderSignedOut() {
        who.visibility = View.GONE
        createBox.visibility = View.VISIBLE
        layoutAdvancedAuth.visibility = View.VISIBLE
        btnToggleAdvancedAuth.visibility = View.GONE
        pairBox.visibility = View.GONE
        pairStart.visibility = View.VISIBLE
        signOut.visibility = View.GONE
        cardPairDevice.visibility = View.GONE
        cardWebCompanion.visibility = View.VISIBLE
        txtAccountWebUrl.text = Server.get(activity)
        cardReplayGuide.visibility = View.VISIBLE
        cardDevices.visibility = View.GONE

        // the last username used is a good guess for this one
        val remembered = Session.phone(activity)
        if (remembered != null && phone.text.isNullOrBlank()) {
            phone.setText(remembered)
        }
    }

    private fun submitAuth() {
        if (busy) return
        val username = phone.text.toString().trim()
        val pass = password.text.toString()

        if (username.isBlank()) {
            say(activity.getString(R.string.username_needed), isError = true)
            return
        }
        if (!username.matches(Regex("^[a-zA-Z0-9]{3,32}$"))) {
            say(activity.getString(R.string.username_invalid), isError = true)
            return
        }
        if (pass.isBlank()) {
            say(activity.getString(R.string.password_needed), isError = true)
            return
        }
        if (pass.length < 6) {
            say(activity.getString(R.string.password_short), isError = true)
            return
        }

        if (mode == AuthMode.LOGIN) {
            login(username, pass)
        } else {
            createAccount(username, pass)
        }
    }

    private fun login(username: String, pass: String) {
        val url = saveServerUrl()
        busy = true
        createButton.isEnabled = false
        say(activity.getString(R.string.logging_in), isError = false)

        TeilenApi.login(
            baseUrl = url,
            phone = username,
            password = pass,
            deviceName = deviceName
        ) { result ->
            busy = false
            createButton.isEnabled = true
            when (result) {
                is TeilenApi.LoginResult.Success -> {
                    Session.save(
                        activity,
                        result.signedIn.token,
                        result.signedIn.phone,
                        result.signedIn.deviceName
                    )
                    status.visibility = View.GONE
                    password.text.clear()
                    render()
                }
                is TeilenApi.LoginResult.InvalidCredentials -> {
                    say(activity.getString(R.string.login_failed, result.message), isError = true)
                }
                is TeilenApi.LoginResult.Failed -> {
                    say(result.message, isError = true)
                }
            }
        }
    }

    private fun createAccount(username: String, pass: String) {
        val url = saveServerUrl()
        busy = true
        createButton.isEnabled = false
        say(activity.getString(R.string.account_creating), isError = false)

        TeilenApi.createAccount(
            baseUrl = url,
            phone = username,
            password = pass,
            displayName = displayName.text.toString().trim(),
            deviceName = deviceName
        ) { result ->
            busy = false
            createButton.isEnabled = true
            when (result) {
                is TeilenApi.CreateResult.Created -> {
                    Session.save(
                        activity,
                        result.signedIn.token,
                        result.signedIn.phone,
                        result.signedIn.deviceName
                    )
                    status.visibility = View.GONE
                    password.text.clear()
                    displayName.text.clear()
                    render()
                }
                is TeilenApi.CreateResult.Taken -> {
                    setAuthMode(AuthMode.LOGIN)
                    say(activity.getString(R.string.username_taken) + " — Enter your password to sign in.", isError = true)
                }
                is TeilenApi.CreateResult.Failed -> say(result.message, isError = true)
            }
        }
    }

    // --------------------------------------------------------------- newcomer pairing

    private fun startPairing() {
        pairing = true
        render()
        val url = saveServerUrl()

        val pairingKey = PairingCrypto.newPairingKey()
        var shownCode: String? = null

        pairQrSpinner.visibility = View.VISIBLE
        pairQr.setImageDrawable(null)
        pairCode.text = ""

        TeilenApi.pairThisDevice(
            url,
            deviceName,
            PairingCrypto.b64(pairingKey.publicKey),
            onOffer = { offer ->
                if (!pairing) return@pairThisDevice
                if (offer == null) {
                    pairing = false
                    say(activity.getString(R.string.pair_failed), isError = true)
                    render()
                    return@pairThisDevice
                }
                shownCode = offer.userCode
                pairCode.text = offer.userCode
                pairStatus.text = activity.getString(R.string.pair_waiting)

                // Generate QR Code bitmap via ZXing
                try {
                    val barcodeEncoder = BarcodeEncoder()
                    val bitmap = barcodeEncoder.encodeBitmap(
                        "teilen://pair?code=${offer.userCode}",
                        BarcodeFormat.QR_CODE,
                        400,
                        400
                    )
                    pairQr.setImageBitmap(bitmap)
                    pairQrSpinner.visibility = View.GONE
                } catch (e: Exception) {
                    pairQrSpinner.visibility = View.GONE
                }

                // 1-Tap copy pairing code
                btnCopyPairCode.setOnClickListener {
                    val cm = activity.getSystemService(ClipboardManager::class.java)
                    cm?.setPrimaryClip(ClipData.newPlainText("Teilen Pairing Code", offer.userCode))
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    Toast.makeText(activity, R.string.code_copied, Toast.LENGTH_SHORT).show()
                }
            },
            onPolled = { poll ->
                if (!pairing) return@pairThisDevice
                when (poll) {
                    is TeilenApi.PairingPoll.Waiting -> Unit
                    is TeilenApi.PairingPoll.Paired -> {
                        pairing = false
                        val packageJson = poll.keyPackage
                        val code = shownCode
                        if (packageJson != null && code != null) {
                            try {
                                PairingCrypto.openFromApprover(
                                    activity, packageJson, pairingKey.secretKey, code
                                )
                            } catch (e: Exception) {
                                // paired without key package
                            }
                        }
                        Session.save(
                            activity, poll.signedIn.token, poll.signedIn.phone,
                            poll.signedIn.deviceName
                        )
                        render()
                    }
                    is TeilenApi.PairingPoll.Failed -> {
                        pairing = false
                        say(poll.message, isError = true)
                        render()
                    }
                }
            }
        )
    }

    private fun renderPairing() {
        who.visibility = View.GONE
        createBox.visibility = View.GONE
        pairBox.visibility = View.VISIBLE
        pairStart.visibility = View.GONE
        signOut.visibility = View.GONE
        cardPairDevice.visibility = View.GONE
    }

    // ---------------------------------------------------------------- approver pairing

    fun handleApproveCode(rawCode: String) {
        val code = PairActivity.cleanCode(rawCode)
        if (code == null) {
            sayApproveStatus(activity.getString(R.string.pair_no_code), isError = true)
            return
        }

        sayApproveStatus(activity.getString(R.string.pair_approving), isError = false)
        val serverUrl = Server.get(activity)

        TeilenApi.describePairing(serverUrl, code) { preview ->
            if (preview == null) {
                sayApproveStatus(activity.getString(R.string.pair_code_stale, code), isError = true)
                return@describePairing
            }

            if (preview.mode == "HOST_INVITE") {
                val devName = listOfNotNull(Build.MANUFACTURER, Build.MODEL)
                    .joinToString(" ")
                    .ifBlank { "Android Device" }

                AlertDialog.Builder(activity)
                    .setTitle(R.string.pair_title)
                    .setMessage(activity.getString(R.string.pair_ask_named, preview.deviceName, "Vault"))
                    .setPositiveButton(R.string.pair_allow) { _, _ ->
                        sayApproveStatus("Connecting to ${preview.deviceName}…", isError = false)
                        val myMergePkg = try { PairingCrypto.sealHostInvite(activity, code) } catch (e: Exception) { null }
                        val currentToken = Session.token(activity)
                        TeilenApi.claimHostInvite(
                            baseUrl = serverUrl,
                            userCode = code,
                            deviceName = devName,
                            mergeKeyPackage = myMergePkg,
                            scannerToken = currentToken
                        ) { result ->
                            if (result.ok && result.signedIn != null) {
                                if (result.encryptedKeyBundle != null) {
                                    PairingCrypto.openHostInvite(activity, result.encryptedKeyBundle, code, isPrimaryHost = result.isPrimaryHost)
                                }
                                Session.save(activity, result.signedIn.token, result.signedIn.phone, result.signedIn.deviceName)
                                render()
                                (activity as? MainActivity)?.refreshFeed()
                                (activity as? MainActivity)?.fetchDevicesAndUpdateBanners()
                                sayApproveStatus("Connected!", isError = false)
                                inputApproveCode.text.clear()
                                Toast.makeText(activity, "Connected to ${preview.deviceName}!", Toast.LENGTH_SHORT).show()
                            } else {
                                val err = result.error ?: "Claim failed"
                                sayApproveStatus(err, isError = true)
                                Toast.makeText(activity, err, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    .setNegativeButton(R.string.pair_deny) { _, _ ->
                        approvePairStatus.visibility = View.GONE
                    }
                    .show()
                return@describePairing
            }

            // Newcomer Request: needs approver token
            val mainActivity = activity as? MainActivity
            if (mainActivity != null) {
                mainActivity.ensureAuthenticated(onSuccess = { token ->
                    proceedWithApproveCode(code, token, preview)
                }, onError = { err ->
                    sayApproveStatus(err, isError = true)
                })
            } else {
                val token = Session.token(activity)
                if (token != null) {
                    proceedWithApproveCode(code, token, preview)
                } else {
                    sayApproveStatus("Authentication required", isError = true)
                }
            }
        }
    }

    private fun proceedWithApproveCode(code: String, token: String, preview: TeilenApi.PairingPreview) {
        val kind = if (preview.deviceType == "PHONE") {
            activity.getString(R.string.pair_kind_phone)
        } else {
            activity.getString(R.string.pair_kind_web)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.pair_title)
            .setMessage(activity.getString(R.string.pair_ask_named, preview.deviceName, kind))
            .setPositiveButton(R.string.pair_allow) { _, _ ->
                executePairingDecision(code, token, allow = true, newcomerPublicKey = preview.publicKey)
            }
            .setNegativeButton(R.string.pair_deny) { _, _ ->
                executePairingDecision(code, token, allow = false, newcomerPublicKey = null)
            }
            .setOnCancelListener {
                approvePairStatus.visibility = View.GONE
            }
            .show()
    }

    private fun executePairingDecision(code: String, token: String, allow: Boolean, newcomerPublicKey: String?) {
        sayApproveStatus(activity.getString(if (allow) R.string.pair_approving else R.string.pair_denying), isError = false)
        val keyPackage = if (allow && newcomerPublicKey != null) {
            try {
                PairingCrypto.sealForNewcomer(activity, PairingCrypto.unb64(newcomerPublicKey), code)
            } catch (e: Exception) {
                null
            }
        } else null

        val mergePkg = if (allow) {
            try { PairingCrypto.sealHostInvite(activity, code) } catch (e: Exception) { null }
        } else null

        TeilenApi.decidePairing(Server.get(activity), token, code, allow, keyPackage, mergePkg) { ok, message ->
            if (ok) {
                val said = activity.getString(if (allow) R.string.pair_allowed else R.string.pair_refused, message)
                sayApproveStatus(said, isError = false)
                inputApproveCode.text.clear()
                Toast.makeText(activity, said, Toast.LENGTH_LONG).show()
            } else {
                val err = activity.getString(R.string.pair_failed_reason, message)
                sayApproveStatus(err, isError = true)
                Toast.makeText(activity, err, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun sayApproveStatus(text: String, isError: Boolean) {
        approvePairStatus.visibility = View.VISIBLE
        approvePairStatus.text = text
        approvePairStatus.setTextColor(
            activity.getColor(if (isError) R.color.danger else R.color.text_secondary)
        )
    }

    // ---------------------------------------------------------------- paired

    private fun renderPaired() {
        who.visibility = View.VISIBLE
        who.text = Session.phone(activity)?.let {
            activity.getString(R.string.signed_in_as, it)
        } ?: activity.getString(R.string.signed_in)
        createBox.visibility = if (advancedAuthExpanded) View.VISIBLE else View.GONE
        layoutAdvancedAuth.visibility = if (advancedAuthExpanded) View.VISIBLE else View.GONE
        btnToggleAdvancedAuth.visibility = View.VISIBLE
        pairBox.visibility = View.GONE
        pairStart.visibility = View.GONE
        signOut.visibility = View.VISIBLE
        cardPairDevice.visibility = View.VISIBLE
        approvePairStatus.visibility = View.GONE

        cardWebCompanion.visibility = View.VISIBLE
        txtAccountWebUrl.text = Server.get(activity)
        cardReplayGuide.visibility = View.VISIBLE
        loadDevices()
    }

    fun loadDevices() {
        val token = Session.token(activity) ?: return
        TeilenApi.getDevices(Server.get(activity), token) { devices ->
            if (devices != null) {
                cardDevices.visibility = View.VISIBLE
                containerDevicesList.removeAllViews()
                val inflater = LayoutInflater.from(activity)
                for (dev in devices) {
                    val row = inflater.inflate(R.layout.item_device_row, containerDevicesList, false)
                    val icon = row.findViewById<ImageView>(R.id.device_item_icon)
                    val name = row.findViewById<TextView>(R.id.device_item_name)
                    val currentBadge = row.findViewById<View>(R.id.device_item_current_badge)
                    val type = row.findViewById<TextView>(R.id.device_item_type)

                    name.text = dev.name
                    if (dev.current) {
                        currentBadge.visibility = View.VISIBLE
                    } else {
                        currentBadge.visibility = View.GONE
                    }
                    val isPhone = dev.deviceType.equals("PHONE", ignoreCase = true)
                    icon.setImageResource(if (isPhone) R.drawable.ic_tab_send else R.drawable.ic_tab_feed)
                    type.text = if (isPhone) "Mobile Device" else "Web Browser"
                    containerDevicesList.addView(row)
                }
                onDevicesCountChanged(devices.size)
            }
        }
    }

    private fun signOut() {
        Session.clear(activity)
        password.text.clear()
        status.visibility = View.GONE
        approvePairStatus.visibility = View.GONE
        inputApproveCode.text.clear()
        render()
    }

    // ----------------------------------------------------------------- shared

    private fun saveServerUrl(): String {
        val field: EditText = activity.findViewById(R.id.server)
        val url = Server.normalize(field.text.toString())
        Server.set(activity, url)
        field.setText(url)
        return url
    }

    private fun say(text: String, isError: Boolean) {
        status.visibility = View.VISIBLE
        status.text = text
        status.setTextColor(
            activity.getColor(if (isError) R.color.danger else R.color.text_secondary)
        )
    }
}

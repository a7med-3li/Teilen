package com.teilen.app

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/**
 * The account management component: supports logging in with phone & password,
 * creating an account, pairing via code, and managing active session.
 */
class AccountCard(
    private val activity: Activity,
    private val onPaired: () -> Unit
) {

    enum class AuthMode { LOGIN, REGISTER }

    private val createBox: View = activity.findViewById(R.id.account_create)
    private val pairBox: View = activity.findViewById(R.id.account_pair)
    private val who: TextView = activity.findViewById(R.id.account_who)
    private val phone: EditText = activity.findViewById(R.id.phone)
    private val password: EditText = activity.findViewById(R.id.password)
    private val displayName: EditText = activity.findViewById(R.id.display_name)
    private val createButton: Button = activity.findViewById(R.id.create_account)
    private val pairStart: Button = activity.findViewById(R.id.account_pair_start)
    private val signOut: Button = activity.findViewById(R.id.sign_out)
    private val pairCode: TextView = activity.findViewById(R.id.pair_code)
    private val pairStatus: TextView = activity.findViewById(R.id.pair_status)
    private val pairCancel: Button = activity.findViewById(R.id.pair_cancel)
    private val status: TextView = activity.findViewById(R.id.account_status)

    private val authSwitchLogin: Button = activity.findViewById(R.id.auth_switch_login)
    private val authSwitchRegister: Button = activity.findViewById(R.id.auth_switch_register)
    private val authIntro: TextView = activity.findViewById(R.id.auth_intro)

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
        pairBox.visibility = View.GONE
        pairStart.visibility = View.VISIBLE
        signOut.visibility = View.GONE

        // the last number used is a good guess for this one
        val remembered = Session.phone(activity)
        if (remembered != null && phone.text.isNullOrBlank()) {
            phone.setText(remembered)
        }
    }

    private fun submitAuth() {
        if (busy) return
        val number = phone.text.toString().trim()
        val pass = password.text.toString()

        if (number.isBlank()) {
            say(activity.getString(R.string.phone_needed), isError = true)
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
            login(number, pass)
        } else {
            createAccount(number, pass)
        }
    }

    private fun login(number: String, pass: String) {
        val url = saveServerUrl()
        busy = true
        createButton.isEnabled = false
        say(activity.getString(R.string.logging_in), isError = false)

        TeilenApi.login(
            baseUrl = url,
            phone = number,
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

    private fun createAccount(number: String, pass: String) {
        val url = saveServerUrl()
        busy = true
        createButton.isEnabled = false
        say(activity.getString(R.string.account_creating), isError = false)

        TeilenApi.createAccount(
            baseUrl = url,
            phone = number,
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
                    say(result.message + " — Enter your password to sign in.", isError = true)
                }
                is TeilenApi.CreateResult.Failed -> say(result.message, isError = true)
            }
        }
    }

    // --------------------------------------------------------------- pairing

    private fun startPairing() {
        pairing = true
        render()
        val url = saveServerUrl()

        val pairingKey = PairingCrypto.newPairingKey()
        var shownCode: String? = null

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
                pairCode.text = offer.userCode
                shownCode = offer.userCode
                pairStatus.text = activity.getString(R.string.pair_waiting)
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
    }

    // ---------------------------------------------------------------- paired

    private fun renderPaired() {
        who.visibility = View.VISIBLE
        who.text = Session.phone(activity)?.let {
            activity.getString(R.string.signed_in_as, it)
        } ?: activity.getString(R.string.signed_in)
        createBox.visibility = View.GONE
        pairBox.visibility = View.GONE
        pairStart.visibility = View.GONE
        signOut.visibility = View.VISIBLE
    }

    private fun signOut() {
        Session.clear(activity)
        password.text.clear()
        status.visibility = View.GONE
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

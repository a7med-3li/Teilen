package com.teilen.app

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/**
 * The account card on the main screen, and the only thing that changes what the rest of the app can
 * do: without a token, sending is hidden outright rather than left to fail.
 *
 * Three shapes, one card:
 *
 *  - **signed out** — type a number and claim it, or pair this phone from a device you already have
 *  - **waiting** — a code to type into another device, while this one polls for its token
 *  - **paired** — which account this is, and a way out
 */
class AccountCard(
    private val activity: Activity,
    private val onPaired: () -> Unit
) {

    private val createBox: View = activity.findViewById(R.id.account_create)
    private val pairBox: View = activity.findViewById(R.id.account_pair)
    private val who: TextView = activity.findViewById(R.id.account_who)
    private val phone: EditText = activity.findViewById(R.id.phone)
    private val displayName: EditText = activity.findViewById(R.id.display_name)
    private val createButton: Button = activity.findViewById(R.id.create_account)
    private val pairStart: Button = activity.findViewById(R.id.account_pair_start)
    private val signOut: Button = activity.findViewById(R.id.sign_out)
    private val pairCode: TextView = activity.findViewById(R.id.pair_code)
    private val pairStatus: TextView = activity.findViewById(R.id.pair_status)
    private val pairCancel: Button = activity.findViewById(R.id.pair_cancel)
    private val status: TextView = activity.findViewById(R.id.account_status)

    /** the name the backend will list this device under in the account's device list */
    private val deviceName: String =
        listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL)
            .filterNot { it.isNullOrBlank() }
            .joinToString(" ")
            .ifBlank { "this phone" }

    /** set while a create request is in flight, so the button cannot be pressed twice */
    private var busy = false
    private var pairing = false

    init {
        createButton.setOnClickListener { createAccount() }
        pairStart.setOnClickListener { startPairing() }
        pairCancel.setOnClickListener { pairing = false; render() }
        signOut.setOnClickListener { signOut() }
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

        // the last number used is a good guess for this one; it is never sent without a tap
        val remembered = Session.phone(activity)
        if (remembered != null && phone.text.isNullOrBlank()) {
            phone.setText(remembered)
        }
    }

    private fun createAccount() {
        if (busy) {
            return
        }
        val number = phone.text.toString().trim()
        if (number.isBlank()) {
            say(activity.getString(R.string.phone_needed), isError = true)
            return
        }
        val url = saveServerUrl()

        busy = true
        createButton.isEnabled = false
        say(activity.getString(R.string.account_creating), isError = false)

        TeilenApi.createAccount(
            baseUrl = url,
            phone = number,
            displayName = displayName.text.toString().trim(),
            deviceName = deviceName
        ) { result ->
            busy = false
            createButton.isEnabled = true
            when (result) {
                is TeilenApi.CreateResult.Created -> {
                    Session.save(activity, result.signedIn.token, result.signedIn.phone,
                        result.signedIn.deviceName)
                    status.visibility = View.GONE
                    phone.text.clear()
                    displayName.text.clear()
                    render()
                }
                // somebody already owns this number: the way in from here is an approval, not a claim
                is TeilenApi.CreateResult.Taken -> {
                    say(result.message, isError = true)
                    startPairing()
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

        TeilenApi.pairThisDevice(url, deviceName, onOffer = { offer ->
            if (!pairing) {
                return@pairThisDevice
            }
            if (offer == null) {
                pairing = false
                say(activity.getString(R.string.pair_failed), isError = true)
                render()
                return@pairThisDevice
            }
            pairCode.text = offer.userCode
            pairStatus.text = activity.getString(R.string.pair_waiting)
        }, onPolled = { poll ->
            if (!pairing) {
                return@pairThisDevice
            }
            when (poll) {
                is TeilenApi.PairingPoll.Waiting -> Unit // nothing to say; the code is still up
                is TeilenApi.PairingPoll.Paired -> {
                    pairing = false
                    Session.save(activity, poll.signedIn.token, poll.signedIn.phone,
                        poll.signedIn.deviceName)
                    render()
                }
                // expired, denied, or a code the server has never heard of: the loop has stopped
                is TeilenApi.PairingPoll.Failed -> {
                    pairing = false
                    say(poll.message, isError = true)
                    render()
                }
            }
        })
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
        status.visibility = View.GONE
        render()
    }

    // ----------------------------------------------------------------- shared

    /** the server card sits lower down the screen; whatever it says counts as the answer here too */
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

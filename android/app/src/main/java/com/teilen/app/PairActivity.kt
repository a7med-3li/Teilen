package com.teilen.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/**
 * The far end of pairing: the phone's camera opens {@code teilen://pair?code=…} and lands here.
 *
 * Everything about this screen is a security decision, so it is deliberately dull: one dialog,
 * naming the device that is asking, saying plainly what allowing it means, and two buttons. No
 * deep view hierarchy to get lost in, and nothing is granted until the user says so.
 */
class PairActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val code = codeOf(intent?.data)
        if (code == null) {
            // a bare teilen:// link, or a stale one from another app: nothing to approve
            Toast.makeText(this, R.string.pair_no_code, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val token = Session.token(this)
        if (token == null) {
            // approving needs a token of its own, so this phone has to be paired first
            promptNotPaired()
            return
        }

        // the code is the secret, so ask the server what it is asking for before asking the user
        TeilenApi.describePairing(Server.get(this), code) { preview ->
            // whatever the server could tell us, the decision is the same dialog
            show(code, token, preview)
        }
    }

    /**
     * @param preview what the server says about the code, or null when it has none — unknown,
     *                already spent, or out of time. In that case there is nothing to approve, so
     *                the allow button is not offered rather than offered and then refused.
     */
    private fun show(code: String, token: String, preview: TeilenApi.PairingPreview?) {
        if (isFinishing) {
            return
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.pair_title)

        if (preview == null) {
            builder
                .setMessage(getString(R.string.pair_code_stale, code))
                .setPositiveButton(R.string.pair_close) { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
            return
        }

        val kind = when (preview.deviceType) {
            "PHONE" -> getString(R.string.pair_kind_phone)
            else -> getString(R.string.pair_kind_web)
        }
        builder
            .setMessage(getString(R.string.pair_ask_named, preview.deviceName, kind))
            .setPositiveButton(R.string.pair_allow) { _, _ -> decide(code, token, allow = true) }
            .setNegativeButton(R.string.pair_deny) { _, _ -> decide(code, token, allow = false) }
            // backing out is a "no": the code just stays pending until it runs out
            .setOnCancelListener { finish() }
            .show()
    }

    private fun decide(code: String, token: String, allow: Boolean) {
        val pending = getString(
            if (allow) R.string.pair_approving else R.string.pair_denying
        )
        Toast.makeText(this, pending, Toast.LENGTH_SHORT).show()

        TeilenApi.decidePairing(Server.get(this), token, code, allow) { ok, message ->
            val said = if (ok) {
                getString(if (allow) R.string.pair_allowed else R.string.pair_refused, message)
            } else {
                getString(R.string.pair_failed_reason, message)
            }
            Toast.makeText(this, said, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun promptNotPaired() {
        AlertDialog.Builder(this)
            .setTitle(R.string.pair_title)
            .setMessage(R.string.pair_not_paired)
            .setPositiveButton(R.string.pair_open_app) { _, _ ->
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
                finish()
            }
            .setNegativeButton(R.string.pair_deny) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private companion object {

        /**
         * Exactly the alphabet the server draws codes from: no I or O (they read as 1 and 0), no 0
         * or 1 either. Kept in step with {@code Tokens.CODE_ALPHABET} on the backend.
         *
         * <p>Checked here rather than passed on, because this is the one value that arrives from
         * outside: the code is the whole credential, so a link carrying anything else is refused
         * before it reaches the network. Dashes, spaces and case are cosmetic and get stripped, so
         * what reaches the server is always the eight characters it issued.
         */
        private val CODE_LETTERS = ('A'..'Z').filterNot { it == 'I' || it == 'O' }.toSet() + ('2'..'9').toSet()

        /** {@code teilen://pair?code=K7PM-3XQD} */
        fun codeOf(data: Uri?): String? {
            if (data == null ||
                !data.scheme.equals("teilen", ignoreCase = true) ||
                // the manifest only routes teilen://pair, so anything else was not meant for us
                !data.host.equals("pair", ignoreCase = true)
            ) {
                return null
            }
            val raw = data.getQueryParameter("code") ?: return null
            val compact = raw.uppercase().filter { it in CODE_LETTERS }
            return compact.takeIf { it.length == 8 }
        }
    }
}

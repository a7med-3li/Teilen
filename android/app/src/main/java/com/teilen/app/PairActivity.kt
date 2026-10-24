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
 * Supports both Host Invites (Zero-Auth Claim) and Newcomer Approvals.
 */
class PairActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val data = intent?.data
        if (data != null && data.scheme.equals("teilen", ignoreCase = true) && data.host.equals("session", ignoreCase = true)) {
            startActivity(Intent(this, MainActivity::class.java).setData(data))
            finish()
            return
        }

        val code = codeOf(intent?.data)
        if (code == null) {
            Toast.makeText(this, R.string.pair_no_code, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        TeilenApi.describePairing(Server.get(this), code) { preview ->
            if (preview == null) {
                showStale(code)
                return@describePairing
            }

            if (preview.mode == "HOST_INVITE") {
                handleHostInvite(code, preview)
            } else {
                handleNewcomerApproval(code, preview)
            }
        }
    }

    private fun handleHostInvite(code: String, preview: TeilenApi.PairingPreview) {
        val devName = listOfNotNull(android.os.Build.MANUFACTURER, android.os.Build.MODEL)
            .joinToString(" ")
            .ifBlank { "Android Device" }

        AlertDialog.Builder(this)
            .setTitle(R.string.pair_title)
            .setMessage(getString(R.string.pair_ask_named, preview.deviceName, "Vault"))
            .setPositiveButton(R.string.pair_allow) { _, _ ->
                Toast.makeText(this, "Connecting…", Toast.LENGTH_SHORT).show()
                val myMergePkg = try { PairingCrypto.sealHostInvite(this, code) } catch (e: Exception) { null }
                val currentToken = Session.token(this)
                TeilenApi.claimHostInvite(
                    baseUrl = Server.get(this),
                    userCode = code,
                    deviceName = devName,
                    mergeKeyPackage = myMergePkg,
                    scannerToken = currentToken
                ) { result ->
                    if (result.ok && result.signedIn != null) {
                        if (result.encryptedKeyBundle != null) {
                            PairingCrypto.openHostInvite(this, result.encryptedKeyBundle, code, isPrimaryHost = result.isPrimaryHost)
                        }
                        Session.save(this, result.signedIn.token, result.signedIn.phone, result.signedIn.deviceName)
                        Toast.makeText(this, "Connected to ${preview.deviceName}!", Toast.LENGTH_SHORT).show()
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    } else {
                        Toast.makeText(this, result.error ?: "Connection failed", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }
            .setNegativeButton(R.string.pair_deny) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun handleNewcomerApproval(code: String, preview: TeilenApi.PairingPreview) {
        val token = Session.token(this)
        if (token != null) {
            showApprovalDialog(code, token, preview)
        } else {
            ensureSessionThenApprove(code, preview)
        }
    }

    private fun ensureSessionThenApprove(code: String, preview: TeilenApi.PairingPreview) {
        SecureKeyManager.ensureMasterKey(this)
        val rand = java.util.UUID.randomUUID().toString().replace("-", "").take(10)
        val handle = "vault$rand"
        val password = java.util.UUID.randomUUID().toString().replace("-", "")
        val devName = listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL)
            .filterNot { it.isNullOrBlank() }
            .joinToString(" ")
            .ifBlank { "Android Device" }

        TeilenApi.createAccount(
            baseUrl = Server.get(this),
            phone = handle,
            password = password,
            displayName = "Personal Vault",
            deviceName = devName
        ) { result ->
            when (result) {
                is TeilenApi.CreateResult.Created -> {
                    Session.save(this, result.signedIn.token, handle, devName)
                    showApprovalDialog(code, result.signedIn.token, preview)
                }
                else -> {
                    Toast.makeText(this, R.string.pair_failed, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun showStale(code: String) {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle(R.string.pair_title)
            .setMessage(getString(R.string.pair_code_stale, code))
            .setPositiveButton(R.string.pair_close) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun showApprovalDialog(code: String, token: String, preview: TeilenApi.PairingPreview) {
        if (isFinishing) return
        val kind = when (preview.deviceType) {
            "PHONE" -> getString(R.string.pair_kind_phone)
            else -> getString(R.string.pair_kind_web)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pair_title)
            .setMessage(getString(R.string.pair_ask_named, preview.deviceName, kind))
            .setPositiveButton(R.string.pair_allow) { _, _ ->
                decide(code, token, allow = true, newcomerPublicKey = preview.publicKey)
            }
            .setNegativeButton(R.string.pair_deny) { _, _ ->
                decide(code, token, allow = false, newcomerPublicKey = null)
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun decide(code: String, token: String, allow: Boolean, newcomerPublicKey: String?) {
        val pending = getString(
            if (allow) R.string.pair_approving else R.string.pair_denying
        )
        Toast.makeText(this, pending, Toast.LENGTH_SHORT).show()

        var keyPackage: String? = null
        if (allow && newcomerPublicKey != null) {
            keyPackage = try {
                PairingCrypto.sealForNewcomer(this, PairingCrypto.unb64(newcomerPublicKey), code)
            } catch (e: Exception) {
                null
            }
        }

        val mergePkg = if (allow) {
            try { PairingCrypto.sealHostInvite(this, code) } catch (e: Exception) { null }
        } else null

        TeilenApi.decidePairing(Server.get(this), token, code, allow, keyPackage, mergePkg) { ok, message ->
            val said = if (ok) {
                getString(if (allow) R.string.pair_allowed else R.string.pair_refused, message)
            } else {
                getString(R.string.pair_failed_reason, message)
            }
            Toast.makeText(this, said, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    companion object {
        val CODE_LETTERS = ('A'..'Z').filterNot { it == 'I' || it == 'O' }.toSet() + ('2'..'9').toSet()

        fun codeOf(data: Uri?): String? {
            if (data == null ||
                !data.scheme.equals("teilen", ignoreCase = true) ||
                !data.host.equals("pair", ignoreCase = true)
            ) {
                return null
            }
            val raw = data.getQueryParameter("code") ?: return null
            val compact = raw.uppercase().filter { it in CODE_LETTERS }
            return compact.takeIf { it.length == 8 }
        }

        fun cleanCode(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.startsWith("teilen://", ignoreCase = true)) {
                return codeOf(Uri.parse(trimmed))
            }
            val compact = trimmed.uppercase().filter { it in CODE_LETTERS }
            return compact.takeIf { it.length == 8 }
        }
    }
}

package com.teilen.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where this device's secrets live: the account key that everything shared is sealed under, and the
 * Ed25519 identity it will sign the WebRTC handshake with.
 *
 * Neither is ever written in the clear. Each is wrapped with an AES-256-GCM key that is generated in
 * and never leaves the Android Keystore, so copying the preferences file off the device gives up
 * nothing. The Keystore key may be tied to this install only — losing it just means pairing again.
 */
object SecureKeyManager {

    private const val PREFS = "teilen_secrets"
    private const val KEY_MASTER = "account_master_key"
    private const val KEY_IDENTITY = "identity_ed25519_seed"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "teilen_secret_wrap"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    private val lock = Any()

    /** @param publicKey the 32-byte Ed25519 public key; @param secretKey the 64-byte secret key */
    class Identity(val publicKey: ByteArray, val secretKey: ByteArray)

    // ------------------------------------------------------------ account key

    fun getMasterKey(context: Context): ByteArray? = read(context, KEY_MASTER)

    /** the account key, minted on first use — the first device that pairs seeds it */
    fun ensureMasterKey(context: Context): ByteArray {
        getMasterKey(context)?.let { return it }
        val fresh = PairingCrypto.randomBytes(32)
        setMasterKey(context, fresh)
        return fresh
    }

    fun setMasterKey(context: Context, key: ByteArray) {
        write(context, KEY_MASTER, key)
    }

    // ------------------------------------------------------------- identity

    fun getIdentity(context: Context): Identity? =
        read(context, KEY_IDENTITY)?.let { PairingCrypto.identityFromSeed(it) }

    /** this device's signing identity, made once and kept; used from the WebRTC handshake onward */
    fun ensureIdentity(context: Context): Identity {
        getIdentity(context)?.let { return it }
        val seed = PairingCrypto.randomBytes(32)
        write(context, KEY_IDENTITY, seed)
        return PairingCrypto.identityFromSeed(seed)
    }

    // ------------------------------------------------------- sealed at rest

    private fun read(context: Context, name: String): ByteArray? = synchronized(lock) {
        val stored = prefs(context).getString(name, null) ?: return null
        val blob = try {
            Base64.decode(stored, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (blob.size <= IV_BYTES) {
            return null
        }
        val iv = blob.copyOfRange(0, IV_BYTES)
        val cipherText = blob.copyOfRange(IV_BYTES, blob.size)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(cipherText)
        } catch (e: Exception) {
            // a Keystore key that is gone (reinstall, lock-screen reset) reads as "no key yet"
            null
        }
    }

    private fun write(context: Context, name: String, value: ByteArray) = synchronized(lock) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrapKey())
        val blob = cipher.iv + cipher.doFinal(value)
        prefs(context).edit()
            .putString(name, Base64.encodeToString(blob, Base64.NO_WRAP))
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** the AES key that does the wrapping; created on first use and never exported */
    private fun wrapKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}

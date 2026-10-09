package com.teilen.app

import android.content.Context
import android.util.Base64
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.SecretBox
import com.goterl.lazysodium.interfaces.Sign
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The pairing key exchange, and the primitives the rest of the sealed path is built on.
 *
 * Pairing is the only time the account key moves, and it moves sealed. The device being paired sends
 * a throwaway X25519 public key with its code request; the approving device makes an ephemeral X25519
 * keypair of its own, derives a wrapping key from the two, seals the account key to it, signs the
 * result with its Ed25519 identity, and hands the lot to the server — which can only relay it.
 *
 * The wire shapes are deliberately identical to the browser's `cryptoStore.js`: same X25519, same
 * RFC 5869 HKDF-SHA256 over the raw shared secret, same secretbox. That is what lets a phone and a
 * browser pair in either direction.
 */
object PairingCrypto {

    private const val SIGN_CONTEXT = "teilen-pairing-v1"
    private const val HKDF_SALT = "teilen-pairing"
    private const val HKDF_INFO = "teilen-master-key"
    private const val VERSION = 1
    private const val KEY_BYTES = 32
    private const val X25519_PUBLIC = 32
    private const val X25519_SECRET = 32

    private val sodium = LazySodiumAndroid(SodiumAndroid())

    /** a single-use X25519 keypair, made fresh for every pairing attempt */
    class PairingKey(val publicKey: ByteArray, val secretKey: ByteArray)

    fun randomBytes(count: Int): ByteArray = sodium.randomBytesBuf(count)

    /** standard, padded base64 — the same bytes the browser's libsodium produces */
    fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun unb64(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

    /** user codes are written every which way; the signature is over the compact form */
    fun compact(code: String): String = code.uppercase().filter { it.isLetterOrDigit() }

    fun newPairingKey(): PairingKey {
        val publicKey = ByteArray(X25519_PUBLIC)
        val secretKey = ByteArray(X25519_SECRET)
        sodium.cryptoBoxKeypair(publicKey, secretKey)
        return PairingKey(publicKey, secretKey)
    }

    /** an Ed25519 identity from its 32-byte seed, used to load the stored one */
    fun identityFromSeed(seed: ByteArray): SecureKeyManager.Identity {
        val publicKey = ByteArray(Sign.ED25519_PUBLICKEYBYTES)
        val secretKey = ByteArray(Sign.ED25519_SECRETKEYBYTES)
        sodium.cryptoSignSeedKeypair(publicKey, secretKey, seed)
        return SecureKeyManager.Identity(publicKey, secretKey)
    }

    /**
     * Approving side: seal this device's account key for the newcomer.
     *
     * @param newcomerPublicKey the X25519 key the newcomer offered
     * @param userCode the code both sides can see, bound into the signature
     * @return the JSON bundle to hand back through the server, unreadable to it
     */
    fun sealForNewcomer(context: Context, newcomerPublicKey: ByteArray, userCode: String): String {
        val master = SecureKeyManager.ensureMasterKey(context)
        val identity = SecureKeyManager.ensureIdentity(context)

        val ephemeral = newPairingKey()
        val shared = scalarMult(ephemeral.secretKey, newcomerPublicKey)
        val nonce = randomBytes(SecretBox.NONCEBYTES)
        val wrapped = secretBoxEasy(master, nonce, wrappingKey(shared))

        val pk = b64(ephemeral.publicKey)
        val nonceText = b64(nonce)
        val wrappedText = b64(wrapped)
        val signature = signDetached(
            signingMessage(userCode, pk, nonceText, wrappedText), identity.secretKey
        )

        return JSONObject()
            .put("v", VERSION)
            .put("pk", pk)
            .put("nonce", nonceText)
            .put("wrapped", wrappedText)
            .put("signPk", b64(identity.publicKey))
            .put("sig", b64(signature))
            .toString()
    }

    /**
     * Newcomer side: open the approving device's bundle and keep the account key.
     *
     * @return the account key, now stored, or null when the bundle does not verify or does not open
     */
    fun openFromApprover(
        context: Context,
        bundle: String,
        pairingSecretKey: ByteArray,
        userCode: String
    ): ByteArray? {
        val json = try {
            JSONObject(bundle)
        } catch (e: Exception) {
            return null
        }
        if (json.optInt("v", -1) != VERSION) {
            return null
        }
        val pk = json.optString("pk").ifBlank { return null }
        val nonceText = json.optString("nonce").ifBlank { return null }
        val wrappedText = json.optString("wrapped").ifBlank { return null }
        val signPk = json.optString("signPk").ifBlank { return null }
        val signature = json.optString("sig").ifBlank { return null }

        val message = signingMessage(userCode, pk, nonceText, wrappedText)
        if (!verifyDetached(unb64(signature), message, unb64(signPk))) {
            return null
        }

        val shared = scalarMult(pairingSecretKey, unb64(pk))
        val master = secretBoxOpenEasy(unb64(wrappedText), unb64(nonceText), wrappingKey(shared))
            ?: return null
        SecureKeyManager.setMasterKey(context, master)
        return master
    }

    // ------------------------------------------------------------------ primitives

    private fun wrappingKey(sharedSecret: ByteArray): ByteArray =
        hkdfSha256(sharedSecret, HKDF_SALT.toByteArray(Charsets.UTF_8), HKDF_INFO.toByteArray(Charsets.UTF_8), KEY_BYTES)

    /**
     * RFC 5869 HKDF-SHA256.
     *
     * libsodium's HKDF is not bound in the Android wrapper, so it is written out here over
     * `javax.crypto.Mac` — the same standard construction the browser runs over libsodium's HMAC,
     * which is what makes a phone and a browser derive the same wrapping key.
     */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(salt, ikm)
        val out = ByteArray(length)
        var block = ByteArray(0)
        var filled = 0
        var counter = 1
        while (filled < length) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(block)
            mac.update(info)
            mac.update(counter.toByte())
            block = mac.doFinal()
            val take = minOf(block.size, length - filled)
            System.arraycopy(block, 0, out, filled, take)
            filled += take
            counter++
        }
        return out
    }

    private fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    private fun scalarMult(secretKey: ByteArray, publicKey: ByteArray): ByteArray {
        val shared = ByteArray(X25519_SECRET)
        sodium.cryptoScalarMult(shared, secretKey, publicKey)
        return shared
    }

    private fun secretBoxEasy(message: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        val cipher = ByteArray(message.size + SecretBox.MACBYTES)
        sodium.cryptoSecretBoxEasy(cipher, message, message.size.toLong(), nonce, key)
        return cipher
    }

    private fun secretBoxOpenEasy(cipher: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray? {
        if (cipher.size < SecretBox.MACBYTES) {
            return null
        }
        val message = ByteArray(cipher.size - SecretBox.MACBYTES)
        val ok = sodium.cryptoSecretBoxOpenEasy(message, cipher, cipher.size.toLong(), nonce, key)
        return if (ok) message else null
    }

    private fun signDetached(message: ByteArray, secretKey: ByteArray): ByteArray {
        val signature = ByteArray(Sign.ED25519_BYTES)
        sodium.cryptoSignDetached(signature, message, message.size.toLong(), secretKey)
        return signature
    }

    private fun verifyDetached(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean =
        signature.size == Sign.ED25519_BYTES &&
            sodium.cryptoSignVerifyDetached(signature, message, message.size, publicKey)

    private fun signingMessage(userCode: String, pk: String, nonce: String, wrapped: String): ByteArray =
        listOf(SIGN_CONTEXT, compact(userCode), pk, nonce, wrapped)
            .joinToString("|")
            .toByteArray(Charsets.UTF_8)
}

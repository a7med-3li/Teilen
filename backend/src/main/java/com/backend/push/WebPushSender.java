package com.backend.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Sends the zero-content "something arrived" signal, hand-rolled against RFC 8291 (aes128gcm
 * message encryption) and RFC 8292 (VAPID), because the backend deliberately carries no HTTP or
 * crypto library. Every byte here is standard: P-256 ECDH, HKDF-SHA256, AES-128-GCM.
 *
 * <p>Disabled entirely when no VAPID key pair is configured, so a deployment that does not want
 * push (or has not minted keys yet) simply sends nothing.
 */
@Component
public class WebPushSender {

    private static final Logger log = LoggerFactory.getLogger(WebPushSender.class);

    private static final String CURVE = "secp256r1";
    private static final int RECORD_SIZE = 4096;
    private static final long TTL_SECONDS = 60;
    private static final Duration VAPID_LIFETIME = Duration.ofHours(12);

    /** 404 and 410 mean the browser is gone; the subscription should be dropped, not retried */
    public static final int GONE = 404;
    public static final int GONE_ALT = 410;
    public static final int ACCEPTED = 201;

    private final String vapidPublic;
    private final String vapidPrivate;
    private final String subject;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public WebPushSender(@Value("${teilen.push.vapid-public-key:}") String vapidPublic,
                         @Value("${teilen.push.vapid-private-key:}") String vapidPrivate,
                         @Value("${teilen.push.subject:mailto:admin@teilen.app}") String subject) {
        this.vapidPublic = vapidPublic == null ? "" : vapidPublic.strip();
        this.vapidPrivate = vapidPrivate == null ? "" : vapidPrivate.strip();
        this.subject = subject;
    }

    public boolean enabled() {
        return !vapidPublic.isBlank() && !vapidPrivate.isBlank();
    }

    /** The public key the browser needs to subscribe; empty when push is off. */
    public String publicKey() {
        return enabled() ? vapidPublic : "";
    }

    /**
     * @return the HTTP status the push service answered with, or -1 when it could not be reached
     */
    public int send(PushSubscription subscription, byte[] payload) {
        if (!enabled()) {
            return -1;
        }
        try {
            byte[] body = encrypt(subscription, payload);
            URI endpoint = URI.create(subscription.getEndpoint());
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(10))
                    .header("TTL", Long.toString(TTL_SECONDS))
                    .header("Content-Encoding", "aes128gcm")
                    .header("Content-Type", "application/octet-stream")
                    .header("Authorization", "vapid t=" + vapidToken(endpoint) + ", k=" + vapidPublic)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (Exception e) {
            log.warn("push to {} failed: {}", hostOf(subscription.getEndpoint()), e.toString());
            return -1;
        }
    }

    // ------------------------------------------------------------------ RFC 8291 message

    private byte[] encrypt(PushSubscription subscription, byte[] payload) throws Exception {
        byte[] uaPublic = Base64.getUrlDecoder().decode(subscription.getP256dh());
        byte[] authSecret = Base64.getUrlDecoder().decode(subscription.getAuth());

        KeyPair ephemeral = generateKeyPair();
        byte[] asPublic = uncompressed((ECPublicKey) ephemeral.getPublic());
        byte[] shared = ecdh((ECPrivateKey) ephemeral.getPrivate(), uaPublic);

        // PRK_key = HKDF-Extract(auth_secret, ecdh_secret); IKM = HKDF-Expand(PRK_key, key_info, 32)
        byte[] prkKey = hmac(authSecret, shared);
        byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic);
        byte[] ikm = hkdfExpand(prkKey, keyInfo, 32);

        byte[] salt = random(16);
        byte[] prk = hmac(salt, ikm);
        byte[] cek = hkdfExpand(prk, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = hkdfExpand(prk, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);

        // the last record ends with the 0x02 padding delimiter
        byte[] plaintext = concat(payload, new byte[]{0x02});
        byte[] ciphertext = aesGcm(cek, nonce, plaintext);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(salt);
        body.write(new byte[]{
                (byte) (RECORD_SIZE >>> 24), (byte) (RECORD_SIZE >>> 16),
                (byte) (RECORD_SIZE >>> 8), (byte) RECORD_SIZE});
        body.write(asPublic.length);
        body.write(asPublic);
        body.write(ciphertext);
        return body.toByteArray();
    }

    // ------------------------------------------------------------------ RFC 8292 VAPID

    private String vapidToken(URI endpoint) throws Exception {
        String aud = endpoint.getScheme() + "://" + endpoint.getAuthority();
        long exp = Instant.now().plus(VAPID_LIFETIME).getEpochSecond();

        String header = b64url("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = b64url(("{\"aud\":\"" + aud + "\",\"exp\":" + exp + ",\"sub\":\"" + subject + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + claims;

        ECParameterSpec params = p256();
        PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(
                new ECPrivateKeySpec(new BigInteger(1, Base64.getUrlDecoder().decode(vapidPrivate)), params));
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key);
        signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + b64url(derToRaw(signer.sign()));
    }

    /** JOSE wants the raw R||S pair, not the DER SEQUENCE OpenSSL-shaped signatures come in */
    private static byte[] derToRaw(byte[] der) {
        int offset = 2;
        if ((der[1] & 0x80) != 0) {
            offset = 2 + (der[1] & 0x7f);
        }
        int rLength = der[offset + 1];
        byte[] r = new byte[32];
        byte[] s = new byte[32];
        copyInteger(der, offset + 2, rLength, r);
        int sOffset = offset + 2 + rLength;
        copyInteger(der, sOffset + 2, der[sOffset + 1], s);
        return concat(r, s);
    }

    private static void copyInteger(byte[] der, int from, int length, byte[] into) {
        int start = from;
        while (length > 32 && der[start] == 0) {
            start++;
            length--;
        }
        System.arraycopy(der, start, into, 32 - length, length);
    }

    // ------------------------------------------------------------------ primitives

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec(CURVE));
        return generator.generateKeyPair();
    }

    private static byte[] ecdh(ECPrivateKey own, byte[] rawPublic) throws Exception {
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(own);
        agreement.doPhase(decodePoint(rawPublic), true);
        return agreement.generateSecret();
    }

    private static ECPublicKey decodePoint(byte[] raw) throws Exception {
        BigInteger x = new BigInteger(1, slice(raw, 1, 33));
        BigInteger y = new BigInteger(1, slice(raw, 33, 65));
        return (ECPublicKey) KeyFactory.getInstance("EC")
                .generatePublic(new ECPublicKeySpec(new ECPoint(x, y), p256()));
    }

    private static byte[] uncompressed(ECPublicKey key) {
        byte[] x = stripLeading(key.getW().getAffineX().toByteArray(), 32);
        byte[] y = stripLeading(key.getW().getAffineY().toByteArray(), 32);
        return concat(new byte[]{0x04}, x, y);
    }

    private static ECParameterSpec p256() throws Exception {
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec(CURVE));
        return parameters.getParameterSpec(ECParameterSpec.class);
    }

    private static byte[] aesGcm(byte[] key, byte[] nonce, byte[] plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        return cipher.doFinal(plaintext);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    /** HKDF-Expand, one block deep — every length this needs is 32 bytes or fewer */
    private static byte[] hkdfExpand(byte[] prk, byte[] info, int length) throws Exception {
        byte[] block = hmac(prk, concat(info, new byte[]{0x01}));
        return slice(block, 0, length);
    }

    private static byte[] random(int length) {
        byte[] bytes = new byte[length];
        new java.security.SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static String b64url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hostOf(String endpoint) {
        try {
            return URI.create(endpoint).getHost();
        } catch (RuntimeException e) {
            return "?";
        }
    }

    private static byte[] stripLeading(byte[] value, int length) {
        if (value.length == length) {
            return value;
        }
        byte[] out = new byte[length];
        if (value.length > length) {
            System.arraycopy(value, value.length - length, out, 0, length);
        } else {
            System.arraycopy(value, 0, out, length - value.length, value.length);
        }
        return out;
    }

    private static byte[] slice(byte[] source, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(source, from, out, 0, out.length);
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }
}

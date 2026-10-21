package com.backend.auth.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Validates Ed25519 signatures for Device Proof-of-Possession (DPoP / X-Teilen-Signature).
 * Uses standard RFC 8410 DER prefix with Java's built-in SunEC provider.
 */
@Component
public class Ed25519Verifier {

    private static final Logger log = LoggerFactory.getLogger(Ed25519Verifier.class);
    private static final byte[] ED25519_DER_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    public boolean verify(String base64PublicKey, byte[] message, String base64Signature) {
        if (base64PublicKey == null || base64Signature == null || message == null) {
            return false;
        }
        try {
            byte[] rawPub = Base64.getDecoder().decode(base64PublicKey);
            byte[] sigBytes = Base64.getDecoder().decode(base64Signature);

            byte[] derKey;
            if (rawPub.length == 32) {
                derKey = new byte[ED25519_DER_PREFIX.length + 32];
                System.arraycopy(ED25519_DER_PREFIX, 0, derKey, 0, ED25519_DER_PREFIX.length);
                System.arraycopy(rawPub, 0, derKey, ED25519_DER_PREFIX.length, 32);
            } else if (rawPub.length == 44) {
                derKey = rawPub;
            } else {
                log.warn("Invalid Ed25519 public key length: {}", rawPub.length);
                return false;
            }

            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            PublicKey publicKey = kf.generatePublic(new X509EncodedKeySpec(derKey));

            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(message);
            return verifier.verify(sigBytes);
        } catch (Exception e) {
            log.warn("Ed25519 signature verification failed: {}", e.getMessage());
            return false;
        }
    }
}

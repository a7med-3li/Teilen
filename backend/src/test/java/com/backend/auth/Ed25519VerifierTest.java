package com.backend.auth;

import com.backend.auth.security.Ed25519Verifier;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ed25519VerifierTest {

    private final Ed25519Verifier verifier = new Ed25519Verifier();

    @Test
    void verifiesValidSignatureWithRawPublicKey() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair kp = kpg.generateKeyPair();

        byte[] spki = kp.getPublic().getEncoded();
        byte[] rawPub = Arrays.copyOfRange(spki, spki.length - 32, spki.length);
        String base64Pub = Base64.getEncoder().encodeToString(rawPub);

        byte[] message = "GET|/api/items|1700000000000".getBytes(StandardCharsets.UTF_8);

        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(kp.getPrivate());
        signer.update(message);
        byte[] signature = signer.sign();
        String base64Sig = Base64.getEncoder().encodeToString(signature);

        assertTrue(verifier.verify(base64Pub, message, base64Sig));
        assertFalse(verifier.verify(base64Pub, "TAMPERED".getBytes(StandardCharsets.UTF_8), base64Sig));
    }
}

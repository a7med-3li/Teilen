package com.backend.item;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * A bearer token cannot travel in an {@code <img src>} or a plain link, and the feed needs both:
 * images preview inline, PDFs open in a new tab. So blob bytes are reachable by a signed link
 * instead — the API hands one out with every item it returns, valid until the item itself dies.
 *
 * <p>Links are signed with HMAC-SHA-256 over the item id and the expiry, so a forwarded URL stops
 * working the moment the item expires, and guessing ids gets you nothing.
 */
@Component
public class BlobLinks {

    private static final Logger log = LoggerFactory.getLogger(BlobLinks.class);
    private static final String PARAM = "t";

    private final byte[] key;
    private final Duration lifetime;

    public BlobLinks(@Value("${teilen.auth.blob-link-secret:}") String secret,
                     @Value("${teilen.auth.blob-link-ttl-seconds:900}") long lifetimeSeconds) {
        this.key = secret == null || secret.isBlank() ? randomKey() : secret.getBytes(StandardCharsets.UTF_8);
        this.lifetime = Duration.ofSeconds(lifetimeSeconds);
        if (secret == null || secret.isBlank()) {
            log.info("no teilen.auth.blob-link-secret set: signed blob links use a random key, "
                    + "so they stop working after a restart");
        }
    }

    private static byte[] randomKey() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return bytes;
    }

    /** @return an absolute path with the signature already in it, or null for text items */
    public String linkFor(UUID id, Instant expiresAt) {
        if (id == null) {
            return null;
        }
        Instant expiry = expiresAt == null ? Instant.now().plus(lifetime) : expiresAt;
        // never outlive the item, and never longer than the configured window
        Instant limit = Instant.now().plus(lifetime);
        if (expiry.isAfter(limit)) {
            expiry = limit;
        }
        long millis = expiry.toEpochMilli();
        return "/api/items/" + id + "/blob?" + PARAM + "=" + millis + "-" + signature(id, millis);
    }

    /** @return true when the signature is ours, for this item, and has not expired */
    public boolean isValid(UUID id, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        int dash = token.indexOf('-');
        if (dash <= 0) {
            return false;
        }
        long millis;
        try {
            millis = Long.parseLong(token.substring(0, dash));
        } catch (NumberFormatException e) {
            return false;
        }
        if (Instant.ofEpochMilli(millis).isBefore(Instant.now())) {
            return false;
        }
        return MessageDigest.isEqual(signature(id, millis).getBytes(StandardCharsets.UTF_8),
                token.substring(dash + 1).getBytes(StandardCharsets.UTF_8));
    }

    public static String paramName() {
        return PARAM;
    }

    private String signature(UUID id, long expiryMillis) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(
                    mac.doFinal((id + "|" + expiryMillis).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 is required and unavailable", e);
        }
    }
}

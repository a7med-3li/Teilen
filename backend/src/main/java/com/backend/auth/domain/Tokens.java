package com.backend.auth.domain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Bearer tokens and the codes that go with them. Tokens are 32 random bytes; the database only
 * ever sees their SHA-256, so a dump is not a set of usable tokens.
 */
public final class Tokens {

    /** no I, O, 0 or 1: the code gets read off a screen and typed by hand */
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int USER_CODE_LENGTH = 8;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private Tokens() {
    }

    /** a bearer token: 32 random bytes, URL-safe, no padding */
    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }

    /**
     * @param raw the token as presented; null is treated as empty, so a missing header can never
     *            turn into a NullPointerException in the middle of a request
     */
    public static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((raw == null ? "" : raw).getBytes()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required and missing", e);
        }
    }

    /** "K7PM3XQD" — eight unambiguous characters */
    public static String newUserCode() {
        StringBuilder code = new StringBuilder(USER_CODE_LENGTH);
        for (int i = 0; i < USER_CODE_LENGTH; i++) {
            code.append(CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)]);
        }
        return code.toString();
    }

    /**
     * How the user reads it: K7PM3XQD in the URL and the API, "K7PM-3XQD" on screen and when typed.
     * Spaces and dashes are ignored everywhere, so a phone that scans it and a person that types
     * it end up at the same row.
     */
    public static String formatUserCode(String raw) {
        String compact = normalizeUserCode(raw);
        if (compact.isEmpty()) {
            return "";
        }
        if (compact.length() == 8) {
            return compact.substring(0, 4) + "-" + compact.substring(4);
        }
        if (compact.length() > 8) {
            compact = compact.substring(0, 8);
            return compact.substring(0, 4) + "-" + compact.substring(4);
        }
        return compact;
    }

    public static String normalizeUserCode(String raw) {
        return raw == null ? "" : raw.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
    }

    public static String extractAndNormalizeUserCode(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            return "";
        }
        String input = rawInput.trim();
        // Try to extract from query parameter code= if present
        if (input.contains("code=")) {
            int idx = input.indexOf("code=");
            String after = input.substring(idx + 5);
            // cut at & or #
            int cut = after.indexOf('&');
            if (cut >= 0) {
                after = after.substring(0, cut);
            }
            cut = after.indexOf('#');
            if (cut >= 0) {
                after = after.substring(0, cut);
            }
            input = after;
        }
        // Also handle teilen://pair?code= style
        if (input.toLowerCase().startsWith("teilen://")) {
            // try to extract
            try {
                java.net.URI uri = java.net.URI.create(input);
                String query = uri.getQuery();
                if (query != null && query.contains("code=")) {
                    String[] parts = query.split("&");
                    for (String p : parts) {
                        if (p.startsWith("code=")) {
                            input = p.substring(5);
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return normalizeUserCode(input);
    }

    /**
     * A phone number as an identity: an optional leading '+', digits, nothing else. Anything a
     * person would plausibly type gets accepted ("+49 (0)151 1234567" becomes "+4901511234567");
     * everything else is refused rather than guessed at.
     */
    public static String normalizePhone(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.strip();
        boolean international = trimmed.startsWith("+");
        String digits = trimmed.replaceAll("\\D", "");
        return international ? "+" + digits : digits;
    }

    public static String normalizeUsername(String raw) {
        return raw == null ? "" : raw.strip();
    }
}

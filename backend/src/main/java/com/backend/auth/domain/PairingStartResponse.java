package com.backend.auth.domain;

import java.time.Instant;

/**
 * Answer of {@code POST /api/auth/device/code}.
 *
 * @param deviceCode  poll with this; it is the only proof the caller has that it started this
 * @param userCode    show this — as a QR, or as text to type on a phone
 * @param qrSvgUrl    the same code as a QR, drawn server-side
 * @param pairingUri  what the QR encodes: {@code teilen://pair?code=…}, which any phone camera opens
 */
public record PairingStartResponse(
        String deviceCode,
        String userCode,
        long expiresInSeconds,
        long pollIntervalSeconds,
        String qrSvgUrl,
        String pairingUri,
        Instant expiresAt) {
}

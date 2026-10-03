package com.backend.auth;

import java.time.Instant;

/**
 * What a pairing code is asking for, read before anyone approves it.
 *
 * <p>Only the newcomer's own description of itself — the holder of the code already knows it, and
 * nothing here says which account it would join.
 */
public record PairingPreview(
        String deviceName,
        DeviceType deviceType,
        String platform,
        Instant expiresAt) {

    public static PairingPreview of(PairingRequest request) {
        return new PairingPreview(request.getDeviceName(), request.getDeviceType(),
                request.getPlatform(), request.getExpiresAt());
    }
}

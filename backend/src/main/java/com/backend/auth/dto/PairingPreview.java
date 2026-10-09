package com.backend.auth.dto;

import java.time.Instant;
import com.backend.auth.entity.PairingRequest;
import com.backend.auth.enums.DeviceType;

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
        /** the newcomer's single-use X25519 public key to wrap the account key to, or null */
        String publicKey,
        Instant expiresAt) {

    public static PairingPreview of(PairingRequest request) {
        return new PairingPreview(request.getDeviceName(), request.getDeviceType(),
                request.getPlatform(), request.getNewcomerPublicKey(), request.getExpiresAt());
    }
}

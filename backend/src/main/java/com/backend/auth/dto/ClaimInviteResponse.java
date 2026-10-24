package com.backend.auth.dto;

import java.util.UUID;

public record ClaimInviteResponse(
        String token,
        UUID deviceId,
        String deviceName,
        UUID userId,
        String username,
        String encryptedKeyBundle,
        UUID primaryUserId,
        boolean isPrimaryHost
) {
    public ClaimInviteResponse(String token, UUID deviceId, String deviceName,
                               UUID userId, String username, String encryptedKeyBundle) {
        this(token, deviceId, deviceName, userId, username, encryptedKeyBundle, userId, true);
    }
}

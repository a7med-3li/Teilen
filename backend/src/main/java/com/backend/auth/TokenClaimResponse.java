package com.backend.auth;

import java.util.UUID;

/** The waiter's own token, handed over exactly once. */
public record TokenClaimResponse(
        String token,
        UUID deviceId,
        DeviceType deviceType,
        String deviceName,
        UserResponse user) {
}

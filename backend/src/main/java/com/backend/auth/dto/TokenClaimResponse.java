package com.backend.auth.dto;

import java.util.UUID;
import com.backend.auth.enums.DeviceType;

/**
 * The waiter's own token, handed over exactly once.
 */
public record TokenClaimResponse(
        String token,
        UUID deviceId,
        DeviceType deviceType,
        String deviceName,
        String keyPackage,
        String mergeKeyPackage,
        UserResponse user) {

    public TokenClaimResponse(String token, UUID deviceId, DeviceType deviceType,
                              String deviceName, String keyPackage, UserResponse user) {
        this(token, deviceId, deviceType, deviceName, keyPackage, null, user);
    }
}

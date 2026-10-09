package com.backend.auth.dto;

import java.util.UUID;
import com.backend.auth.enums.DeviceType;

/**
 * The waiter's own token, handed over exactly once.
 *
 * <p>{@code keyPackage} is the approver's sealed account-key bundle, when one was left; the
 * newcomer unwraps it locally. Null for pairings that exchanged no key.
 */
public record TokenClaimResponse(
        String token,
        UUID deviceId,
        DeviceType deviceType,
        String deviceName,
        String keyPackage,
        UserResponse user) {
}

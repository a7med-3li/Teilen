package com.backend.auth.dto;

import java.util.UUID;

/**
 * A token, once, for the device that just paired. The apps store it and send it as
 * {@code Authorization: Bearer …} from then on.
 */
public record SessionResponse(
        String token,
        UUID deviceId,
        String deviceName,
        UserResponse user) {
}

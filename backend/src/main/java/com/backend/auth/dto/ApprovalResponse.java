package com.backend.auth.dto;

import java.time.Instant;
import com.backend.auth.enums.DeviceType;

/** What got paired, so the phone can say so out loud. */
public record ApprovalResponse(
        String deviceName,
        DeviceType deviceType,
        String description,
        Instant expiresAt) {
}

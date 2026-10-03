package com.backend.auth;

import java.time.Instant;

/** What got paired, so the phone can say so out loud. */
public record ApprovalResponse(
        String deviceName,
        DeviceType deviceType,
        String description,
        Instant expiresAt) {
}

package com.backend.auth.dto;

import java.time.Instant;

public record HostInviteResponse(
        String userCode,
        String deviceCode,
        String qrUrl,
        long expiresInSeconds,
        Instant expiresAt
) {}

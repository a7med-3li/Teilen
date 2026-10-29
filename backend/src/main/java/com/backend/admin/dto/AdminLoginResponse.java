package com.backend.admin.dto;

import java.time.Instant;

public record AdminLoginResponse(
        String adminToken,
        String username,
        Instant expiresAt
) {
}

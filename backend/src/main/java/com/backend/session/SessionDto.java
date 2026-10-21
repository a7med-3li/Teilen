package com.backend.session;

import java.time.Instant;
import java.util.UUID;

public record SessionDto(
        UUID id,
        String name,
        boolean isPersonal,
        String role,
        long memberCount,
        Instant createdAt,
        String encryptedSessionKey
) {
}

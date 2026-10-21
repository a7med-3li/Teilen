package com.backend.session;

import java.time.Instant;
import java.util.UUID;

public record SessionMemberDto(
        UUID userId,
        String username,
        String displayName,
        String role,
        Instant joinedAt
) {
}

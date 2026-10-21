package com.backend.session;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SessionDetailDto(
        UUID id,
        String name,
        boolean isPersonal,
        String role,
        List<SessionMemberDto> members,
        Instant createdAt
) {
}

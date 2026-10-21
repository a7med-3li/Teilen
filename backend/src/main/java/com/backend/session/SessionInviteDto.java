package com.backend.session;

import java.util.UUID;

public record SessionInviteDto(
        UUID sessionId,
        String sessionName,
        String inviteCode,
        String inviteUri
) {
}

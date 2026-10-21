package com.backend.session;

import jakarta.validation.constraints.NotBlank;

public record JoinSessionRequest(
        @NotBlank(message = "Invite code is required")
        String inviteCode,

        String encryptedSessionKey
) {
}

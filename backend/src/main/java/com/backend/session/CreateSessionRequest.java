package com.backend.session;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateSessionRequest(
        @NotBlank(message = "Session name is required")
        @Size(min = 1, max = 120, message = "Session name must be between 1 and 120 characters")
        String name,

        String encryptedSessionKey
) {
}

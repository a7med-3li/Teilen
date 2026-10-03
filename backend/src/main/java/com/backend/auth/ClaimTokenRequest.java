package com.backend.auth;

import jakarta.validation.constraints.NotBlank;

/** Body of {@code POST /api/auth/device/token}: the waiting device, polling for its token. */
public record ClaimTokenRequest(
        @NotBlank(message = "deviceCode must not be blank")
        String deviceCode) {
}

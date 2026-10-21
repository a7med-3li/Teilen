package com.backend.auth.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/auth/login} — logging in with username and password.
 */
public record LoginRequest(
        @NotBlank(message = "username must not be blank")
        @Size(max = 40, message = "username must be at most 40 characters")
        @JsonAlias("phone")
        String username,

        @NotBlank(message = "password must not be blank")
        String password,

        /** what the device calls itself, e.g. "Ahmed's Pixel" or "Chrome on Linux" */
        @Size(max = 120, message = "deviceName must be at most 120 characters")
        String deviceName,

        /** PHONE | WEB, optional (defaults to PHONE) */
        String deviceType,

        /** Ed25519 or hardware-backed device public key for DPoP device binding */
        @Size(max = 200, message = "devicePublicKey must be at most 200 characters")
        String devicePublicKey) {

    /** Legacy accessor for backward compatibility */
    public String phone() {
        return username;
    }
}

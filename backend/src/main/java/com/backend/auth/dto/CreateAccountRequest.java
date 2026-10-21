package com.backend.auth.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/auth/account} — creating the account with username and password.
 */
public record CreateAccountRequest(
        @NotBlank(message = "username must not be blank")
        @Size(min = 3, max = 32, message = "Username must be 3–32 characters and contain only letters and numbers (no spaces or special characters).")
        @JsonAlias("phone")
        String username,

        @NotBlank(message = "password must not be blank")
        @Size(min = 6, max = 128, message = "password must be between 6 and 128 characters")
        String password,

        @Size(max = 80, message = "displayName must be at most 80 characters")
        String displayName,

        /** what the device calls itself, e.g. "Ahmed's Pixel" */
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

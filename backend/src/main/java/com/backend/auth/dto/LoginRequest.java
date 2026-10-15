package com.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/auth/login} — logging in with phone number and password.
 */
public record LoginRequest(
        @NotBlank(message = "phone must not be blank")
        @Size(max = 40, message = "phone must be at most 40 characters")
        String phone,

        @NotBlank(message = "password must not be blank")
        String password,

        /** what the device calls itself, e.g. "Ahmed's Pixel" or "Chrome on Linux" */
        @Size(max = 120, message = "deviceName must be at most 120 characters")
        String deviceName,

        /** PHONE | WEB, optional (defaults to PHONE) */
        String deviceType) {
}

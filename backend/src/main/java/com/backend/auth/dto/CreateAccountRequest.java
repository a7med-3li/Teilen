package com.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/auth/account} — creating the account with phone and password.
 */
public record CreateAccountRequest(
        @NotBlank(message = "phone must not be blank")
        @Size(max = 40, message = "phone must be at most 40 characters")
        String phone,

        @NotBlank(message = "password must not be blank")
        @Size(min = 6, max = 128, message = "password must be between 6 and 128 characters")
        String password,

        @Size(max = 80, message = "displayName must be at most 80 characters")
        String displayName,

        /** what the device calls itself, e.g. "Ahmed's Pixel" */
        @Size(max = 120, message = "deviceName must be at most 120 characters")
        String deviceName,

        /** PHONE | WEB, optional (defaults to PHONE) */
        String deviceType) {
}

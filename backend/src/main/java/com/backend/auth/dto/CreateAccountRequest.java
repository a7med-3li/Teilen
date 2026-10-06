package com.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/auth/account} — the first phone, creating the account.
 */
public record CreateAccountRequest(
        @NotBlank(message = "phone must not be blank")
        @Size(max = 40, message = "phone must be at most 40 characters")
        String phone,

        @Size(max = 80, message = "displayName must be at most 80 characters")
        String displayName,

        /** what the phone calls itself, e.g. "Ahmed's Pixel" */
        @Size(max = 120, message = "deviceName must be at most 120 characters")
        String deviceName) {
}

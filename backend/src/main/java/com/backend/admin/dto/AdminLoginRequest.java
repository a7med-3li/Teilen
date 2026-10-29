package com.backend.admin.dto;

import jakarta.validation.constraints.NotBlank;

public record AdminLoginRequest(
        @NotBlank(message = "Username must not be blank") String username,
        @NotBlank(message = "Password must not be blank") String password
) {
}

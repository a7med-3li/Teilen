package com.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record HostInviteRequest(
        @NotBlank(message = "encryptedKeyBundle must not be blank")
        @Size(max = 16000, message = "encryptedKeyBundle must be at most 16000 characters")
        String encryptedKeyBundle,

        @Size(max = 32, message = "userCode must be at most 32 characters")
        String userCode,

        @Size(max = 120, message = "deviceName must be at most 120 characters")
        String deviceName,

        @Size(max = 160, message = "platform must be at most 160 characters")
        String platform
) {}

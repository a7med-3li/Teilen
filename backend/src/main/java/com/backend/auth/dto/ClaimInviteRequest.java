package com.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ClaimInviteRequest(
        @NotBlank(message = "userCode must not be blank")
        String userCode,

        @Size(max = 120, message = "deviceName must be at most 120 characters")
        String deviceName,

        String deviceType,

        @Size(max = 200, message = "devicePublicKey must be at most 200 characters")
        String devicePublicKey,

        @Size(max = 160, message = "platform must be at most 160 characters")
        String platform,

        @Size(max = 16000, message = "mergeKeyPackage must be at most 16000 characters")
        String mergeKeyPackage,

        String scannerToken
) {
    public ClaimInviteRequest(String userCode, String deviceName, String deviceType,
                              String devicePublicKey, String platform) {
        this(userCode, deviceName, deviceType, devicePublicKey, platform, null, null);
    }
}

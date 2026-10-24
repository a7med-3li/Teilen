package com.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DevicePairRequest(
        @NotBlank(message = "userCode must not be blank")
        @Size(max = 20, message = "userCode too long")
        String userCode,

        @Size(max = 16000, message = "scannerMasterKey too large")
        String scannerMasterKey,

        @Size(max = 120, message = "deviceName must be at most 120 characters")
        String deviceName,

        String deviceType,

        @Size(max = 100, message = "clientFingerprint must be at most 100 characters")
        String clientFingerprint
) {}

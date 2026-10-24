package com.backend.auth.dto;

import jakarta.validation.constraints.Size;

public record DeviceCodeRequest(
        @Size(max = 120, message = "deviceName must be at most 120 characters")
        String deviceName,

        String deviceType,

        @Size(max = 16000, message = "masterKeyPackage too large")
        String masterKeyPackage,

        @Size(max = 100, message = "clientFingerprint must be at most 100 characters")
        String clientFingerprint
) {}

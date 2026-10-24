package com.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of approve and deny: the code from the QR, or typed in.
 */
public record PairingDecisionRequest(
        @NotBlank(message = "userCode must not be blank")
        String userCode,

        @Size(max = 16000, message = "keyPackage must be at most 16000 characters")
        String keyPackage,

        @Size(max = 16000, message = "mergeKeyPackage must be at most 16000 characters")
        String mergeKeyPackage
) {
    public PairingDecisionRequest(String userCode, String keyPackage) {
        this(userCode, keyPackage, null);
    }
}

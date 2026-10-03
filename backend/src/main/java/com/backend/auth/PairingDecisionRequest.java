package com.backend.auth;

import jakarta.validation.constraints.NotBlank;

/** Body of approve and deny: the code from the QR, or typed in. */
public record PairingDecisionRequest(
        @NotBlank(message = "userCode must not be blank")
        String userCode) {
}

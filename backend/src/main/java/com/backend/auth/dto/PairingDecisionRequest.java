package com.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of approve and deny: the code from the QR, or typed in.
 *
 * <p>{@code keyPackage} rides along only on approve, and only when the approving device holds an
 * account key and the newcomer offered a public key. It is an end-to-end sealed bundle that this
 * server relays without being able to read — see {@code V4__pairing_key_exchange.sql}.
 */
public record PairingDecisionRequest(
        @NotBlank(message = "userCode must not be blank")
        String userCode,

        @Size(max = 4000, message = "keyPackage must be at most 4000 characters")
        String keyPackage) {
}

package com.backend.auth.domain;

import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/auth/device/code}: a device with nothing yet asking to join.
 */
public record StartPairingRequest(
        @Size(max = 120, message = "deviceName must be at most 120 characters")
        String deviceName,

        /** PHONE or WEB; a browser sends WEB, the app sends PHONE */
        @Size(max = 16, message = "deviceType must be at most 16 characters")
        String deviceType,

        /** user agent or platform, only used to name the newcomer in the approval prompt */
        @Size(max = 160, message = "platform must be at most 160 characters")
        String platform,

        /**
         * The newcomer's single-use X25519 public key, base64. Optional — a device that does not
         * take part in the key exchange sends nothing, and pairing is unchanged.
         */
        @Size(max = 200, message = "publicKey must be at most 200 characters")
        String publicKey) {
}

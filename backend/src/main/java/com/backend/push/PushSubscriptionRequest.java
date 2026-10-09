package com.backend.push;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The browser's {@code PushSubscription.toJSON()}, almost verbatim: an endpoint and the two keys
 * the push service gave it. Nothing here is a Teilen credential, so it is stored as-is.
 */
public record PushSubscriptionRequest(
        @NotBlank @Size(max = 2048) String endpoint,
        @NotNull @Valid Keys keys) {

    public record Keys(
            @NotBlank @Size(max = 255) String p256dh,
            @NotBlank @Size(max = 255) String auth) {
    }
}

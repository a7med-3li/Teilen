package com.backend.auth.domain;

import java.security.Principal;
import java.util.UUID;

/**
 * Who is calling. Set as the {@code Authentication} principal, so controllers get it with
 * {@code @AuthenticationPrincipal} and the websocket handshake can read it off the request.
 */
public record AuthenticatedUser(UUID userId, UUID deviceId, String username, String devicePublicKey) implements Principal {

    public AuthenticatedUser(UUID userId, UUID deviceId, String username) {
        this(userId, deviceId, username, null);
    }

    @Override
    public String getName() {
        return username;
    }

    /** Legacy alias for backward compatibility */
    public String phone() {
        return username;
    }
}

package com.backend.auth.domain;

import java.security.Principal;
import java.util.UUID;

/**
 * Who is calling. Set as the {@code Authentication} principal, so controllers get it with
 * {@code @AuthenticationPrincipal} and the websocket handshake can read it off the request.
 */
public record AuthenticatedUser(UUID userId, UUID deviceId, String phone) implements Principal {

    @Override
    public String getName() {
        return phone;
    }
}

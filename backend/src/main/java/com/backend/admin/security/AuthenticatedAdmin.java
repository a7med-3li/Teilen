package com.backend.admin.security;

import java.security.Principal;
import java.util.UUID;

public record AuthenticatedAdmin(UUID adminId, String username) implements Principal {
    @Override
    public String getName() {
        return username;
    }
}

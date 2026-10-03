package com.backend.auth;

import java.time.Instant;
import java.util.UUID;

/** Who the caller is, as the apps are allowed to know it. */
public record UserResponse(UUID id, String phone, String displayName, Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getPhone(), user.getDisplayName(), user.getCreatedAt());
    }
}

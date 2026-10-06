package com.backend.auth.dto;

import java.time.Instant;
import java.util.UUID;
import com.backend.auth.entity.User;

/** Who the caller is, as the apps are allowed to know it. */
public record UserResponse(UUID id, String phone, String displayName, Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getPhone(), user.getDisplayName(), user.getCreatedAt());
    }
}

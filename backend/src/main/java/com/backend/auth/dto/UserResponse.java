package com.backend.auth.dto;

import com.backend.auth.entity.User;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/** Who the caller is, as the apps are allowed to know it. */
public record UserResponse(
        UUID id,
        String username,
        @JsonProperty("phone")
        String phone,
        String displayName,
        Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getUsername(),
                user.getDisplayName(),
                user.getCreatedAt()
        );
    }
}

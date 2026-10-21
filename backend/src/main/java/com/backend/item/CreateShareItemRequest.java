package com.backend.item;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Body of {@code POST /api/items}. Every field except {@code content} is optional.
 */
public record CreateShareItemRequest(
        @NotBlank(message = "content must not be blank")
        @Size(max = 10_000_000, message = "content must be at most 10 MB")
        String content,

        String type,

        Long ttlSeconds,

        UUID sessionId) {
}

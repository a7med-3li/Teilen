package com.backend.item;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/items}. Every field except {@code content} is optional.
 */
public record CreateShareItemRequest(
        @NotBlank(message = "content must not be blank")
        @Size(max = 20_000, message = "content must be at most 20000 characters")
        String content,

        String type,

        Long ttlSeconds) {
}

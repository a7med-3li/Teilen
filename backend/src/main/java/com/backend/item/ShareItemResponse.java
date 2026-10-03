package com.backend.item;

import java.time.Instant;
import java.util.UUID;

public record ShareItemResponse(
        UUID id,
        String type,
        String content,
        String mimeType,
        Long sizeBytes,
        Instant createdAt,
        Instant expiresAt) {

    public static ShareItemResponse from(ShareItem item) {
        return new ShareItemResponse(
                item.getId(),
                item.getType().name(),
                item.getContent(),
                item.getMimeType(),
                item.getSizeBytes(),
                item.getCreatedAt(),
                item.getExpiresAt());
    }
}

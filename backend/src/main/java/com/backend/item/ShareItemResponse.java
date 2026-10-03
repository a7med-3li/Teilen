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
        Instant expiresAt,
        /**
         * A signed link to the bytes, for the cases a browser cannot send a token on: an inline
         * image preview, a PDF opened in a new tab. Null for text and links.
         */
        String blobUrl) {

    public static ShareItemResponse from(ShareItem item, String blobUrl) {
        return new ShareItemResponse(
                item.getId(),
                item.getType().name(),
                item.getContent(),
                item.getMimeType(),
                item.getSizeBytes(),
                item.getCreatedAt(),
                item.getExpiresAt(),
                item.hasBlob() ? blobUrl : null);
    }
}

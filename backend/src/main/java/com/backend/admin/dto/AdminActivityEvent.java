package com.backend.admin.dto;

public record AdminActivityEvent(
        long id,
        String timestamp,
        String vaultHash,
        String action,
        String description,
        String itemType,
        Long payloadBytes,
        Integer ttlSeconds,
        String platform
) {
}

package com.backend.auth.dto;

import java.util.UUID;

public record DevicePairResponse(
        String token,
        UUID userId,
        String masterKey
) {}

package com.backend.auth.dto;

public record DeviceCodeResponse(
        String userCode,
        String qrSvgUrl
) {}

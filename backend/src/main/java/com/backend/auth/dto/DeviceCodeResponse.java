package com.backend.auth.dto;

public record DeviceCodeResponse(
        String userCode,
        String deviceCode,
        String qrSvgUrl
) {
    public DeviceCodeResponse(String userCode, String qrSvgUrl) {
        this(userCode, null, qrSvgUrl);
    }
}

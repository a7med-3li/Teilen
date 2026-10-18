package com.backend.version;

public record VersionResponse(
        int minAndroidVersionCode,
        int latestAndroidVersionCode
) {}

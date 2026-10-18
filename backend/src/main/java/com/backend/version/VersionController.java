package com.backend.version;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/version")
public class VersionController {

    private final int minAndroidVersionCode;
    private final int latestAndroidVersionCode;

    public VersionController(
            @Value("${teilen.version.min-android-version-code:3}") int minAndroidVersionCode,
            @Value("${teilen.version.latest-android-version-code:4}") int latestAndroidVersionCode) {
        this.minAndroidVersionCode = minAndroidVersionCode;
        this.latestAndroidVersionCode = latestAndroidVersionCode;
    }

    @GetMapping
    public VersionResponse getVersion() {
        return new VersionResponse(minAndroidVersionCode, latestAndroidVersionCode);
    }
}

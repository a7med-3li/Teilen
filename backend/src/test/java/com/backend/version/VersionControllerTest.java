package com.backend.version;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VersionControllerTest {

    @Test
    void getVersionReturnsConfiguredVersionCodes() {
        VersionController controller = new VersionController(3, 4);
        VersionResponse response = controller.getVersion();

        assertEquals(3, response.minAndroidVersionCode());
        assertEquals(4, response.latestAndroidVersionCode());
    }
}

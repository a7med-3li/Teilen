package com.backend.auth;

import java.time.Instant;
import java.util.UUID;

/** One row of the device list: paired when, last used, and whether that is this very browser. */
public record DeviceResponse(
        UUID id,
        String name,
        DeviceType deviceType,
        Instant pairedAt,
        Instant lastSeenAt,
        boolean current) {

    public static DeviceResponse from(Device device, boolean current) {
        return new DeviceResponse(device.getId(), device.getName(), device.getDeviceType(),
                device.getCreatedAt(), device.getLastSeenAt(), current);
    }
}

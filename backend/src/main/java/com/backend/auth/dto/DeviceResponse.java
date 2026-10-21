package com.backend.auth.dto;

import java.time.Instant;
import java.util.UUID;
import com.backend.auth.enums.DeviceType;
import com.backend.auth.entity.Device;

/** One row of the device list: paired when, last used, IP, platform, and whether that is this very browser. */
public record DeviceResponse(
        UUID id,
        String name,
        DeviceType deviceType,
        Instant pairedAt,
        Instant lastSeenAt,
        String ipAddress,
        String platform,
        boolean current) {

    public static DeviceResponse from(Device device, boolean current) {
        return new DeviceResponse(device.getId(), device.getName(), device.getDeviceType(),
                device.getCreatedAt(), device.getLastSeenAt(), device.getIpAddress(), device.getPlatform(), current);
    }
}

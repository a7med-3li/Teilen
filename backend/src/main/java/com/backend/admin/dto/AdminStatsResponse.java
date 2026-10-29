package com.backend.admin.dto;

import java.util.List;
import java.util.Map;

public record AdminStatsResponse(
        LivePulse livePulse,
        List<AdminActivityEvent> recentActivity,
        UsersAndDevices usersAndDevices,
        EphemeralStorage ephemeralStorage,
        SystemHealth systemHealth
) {
    public record LivePulse(
            int onlineUsersCount,
            int openFeedSocketsCount,
            int openSignalSocketsCount,
            long activeUsersLast5m,
            long activeUsersLast1h,
            long activeUsersLast24h
    ) {}

    public record UsersAndDevices(
            long totalUsers,
            long namedAccountsCount,
            long anonymousVaultsCount,
            long totalPairedDevices,
            Map<String, Long> devicesByType,
            Map<String, Long> devicesByPlatform,
            long multiDeviceVaultsCount
    ) {}

    public record LifetimeVolume(
            long today,
            long last7Days,
            long allTime
    ) {}

    public record EphemeralStorage(
            long liveItemsTotal,
            Map<String, Long> liveItemsByType,
            long expiringSoonCount,
            LifetimeVolume lifetimeItemsShared,
            long lifetimeBytesTransferred,
            Map<String, Long> popularTtlChoices,
            long totalCustomRooms,
            long totalRoomMemberships
    ) {}

    public record SystemHealth(
            int activeBlobCount,
            long activeBlobBytes,
            long diskFreeBytes,
            long diskTotalBytes,
            long uptimeSeconds,
            long jvmMemoryUsedMb,
            long jvmMemoryMaxMb,
            double cpuLoadPercent
    ) {}
}

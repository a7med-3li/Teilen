package com.backend.admin.service;

import com.backend.admin.dto.AdminActivityEvent;
import com.backend.admin.dto.AdminStatsResponse;
import com.backend.admin.repository.TelemetryEventRepository;
import com.backend.auth.enums.DeviceType;
import com.backend.auth.repository.DeviceRepository;
import com.backend.auth.repository.UserRepository;
import com.backend.item.BlobStore;
import com.backend.item.ShareItemRepository;
import com.backend.item.ShareItemType;
import com.backend.item.ShareItemWebSocketHandler;
import com.backend.session.SessionMemberRepository;
import com.backend.session.SessionRepository;
import com.backend.signal.SignalWebSocketHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AdminStatsService {

    private final ShareItemWebSocketHandler shareItemWebSocketHandler;
    private final SignalWebSocketHandler signalWebSocketHandler;
    private final UserRepository userRepository;
    private final DeviceRepository deviceRepository;
    private final ShareItemRepository shareItemRepository;
    private final SessionRepository sessionRepository;
    private final SessionMemberRepository memberRepository;
    private final TelemetryEventRepository telemetryRepository;
    private final BlobStore blobStore;
    private final TelemetryService telemetryService;

    public AdminStatsService(ShareItemWebSocketHandler shareItemWebSocketHandler,
                             SignalWebSocketHandler signalWebSocketHandler,
                             UserRepository userRepository,
                             DeviceRepository deviceRepository,
                             ShareItemRepository shareItemRepository,
                             SessionRepository sessionRepository,
                             SessionMemberRepository memberRepository,
                             TelemetryEventRepository telemetryRepository,
                             BlobStore blobStore,
                             TelemetryService telemetryService) {
        this.shareItemWebSocketHandler = shareItemWebSocketHandler;
        this.signalWebSocketHandler = signalWebSocketHandler;
        this.userRepository = userRepository;
        this.deviceRepository = deviceRepository;
        this.shareItemRepository = shareItemRepository;
        this.sessionRepository = sessionRepository;
        this.memberRepository = memberRepository;
        this.telemetryRepository = telemetryRepository;
        this.blobStore = blobStore;
        this.telemetryService = telemetryService;
    }

    @Transactional(readOnly = true)
    public AdminStatsResponse getStats() {
        Instant now = Instant.now();

        // 1. Real-Time "Right Now" Pulse
        Set<UUID> onlineUserIds = new HashSet<>(shareItemWebSocketHandler.getConnectedUserIds());
        onlineUserIds.addAll(signalWebSocketHandler.getConnectedUserIds());
        int onlineUsersCount = onlineUserIds.size();

        int openFeedSockets = shareItemWebSocketHandler.getOpenFeedSocketsCount();
        int openSignalSockets = signalWebSocketHandler.getOpenSignalSocketsCount();

        long active5m = deviceRepository.countActiveUsersSince(now.minus(Duration.ofMinutes(5)));
        long active1h = deviceRepository.countActiveUsersSince(now.minus(Duration.ofHours(1)));
        long active24h = deviceRepository.countActiveUsersSince(now.minus(Duration.ofDays(1)));

        AdminStatsResponse.LivePulse livePulse = new AdminStatsResponse.LivePulse(
                onlineUsersCount,
                openFeedSockets,
                openSignalSockets,
                active5m,
                active1h,
                active24h
        );

        // 2. Recent Live Activity Stream
        List<AdminActivityEvent> recentActivity = telemetryService.getRecentActivity();

        // 3. Total Users, Vaults & Devices Breakdown
        long totalUsers = userRepository.count();
        long anonymousVaults = userRepository.countByUsernameStartingWithIgnoreCase("vault");
        long namedAccounts = Math.max(0, totalUsers - anonymousVaults);
        long totalPairedDevices = deviceRepository.count();

        Map<String, Long> devicesByType = new LinkedHashMap<>();
        for (DeviceType dt : DeviceType.values()) {
            devicesByType.put(dt.name(), 0L);
        }
        for (Object[] row : deviceRepository.countByDeviceType()) {
            if (row != null && row.length == 2 && row[0] != null) {
                DeviceType type = (DeviceType) row[0];
                long count = ((Number) row[1]).longValue();
                devicesByType.put(type.name(), count);
            }
        }

        Map<String, Long> devicesByPlatform = new LinkedHashMap<>();
        devicesByPlatform.put("Android", 0L);
        devicesByPlatform.put("Web", 0L);
        devicesByPlatform.put("Linux", 0L);
        devicesByPlatform.put("Windows", 0L);
        devicesByPlatform.put("macOS", 0L);
        devicesByPlatform.put("iOS", 0L);

        for (Object[] row : deviceRepository.countByPlatform()) {
            if (row != null && row.length == 2) {
                String rawPlatform = row[0] != null ? row[0].toString() : null;
                long count = ((Number) row[1]).longValue();
                String norm = TelemetryService.formatDisplayPlatform(TelemetryService.normalizePlatform(rawPlatform));
                devicesByPlatform.merge(norm, count, Long::sum);
            }
        }

        long multiDeviceVaults = deviceRepository.countMultiDeviceVaults();

        AdminStatsResponse.UsersAndDevices usersAndDevices = new AdminStatsResponse.UsersAndDevices(
                totalUsers,
                namedAccounts,
                anonymousVaults,
                totalPairedDevices,
                devicesByType,
                devicesByPlatform,
                multiDeviceVaults
        );

        // 4. Ephemeral Content & Storage Health
        long liveItemsTotal = shareItemRepository.countByExpiresAtGreaterThan(now);

        Map<String, Long> liveItemsByType = new LinkedHashMap<>();
        for (ShareItemType st : ShareItemType.values()) {
            liveItemsByType.put(st.name(), 0L);
        }
        for (Object[] row : shareItemRepository.countLiveByType(now)) {
            if (row != null && row.length == 2 && row[0] != null) {
                ShareItemType type = (ShareItemType) row[0];
                long count = ((Number) row[1]).longValue();
                liveItemsByType.put(type.name(), count);
            }
        }

        long expiringSoon = shareItemRepository.countExpiringSoon(now, now.plus(Duration.ofMinutes(10)));

        long sharedToday = telemetryRepository.countByEventTypeAndCreatedAtAfter("ITEM_SHARED", now.minus(Duration.ofDays(1)));
        long shared7Days = telemetryRepository.countByEventTypeAndCreatedAtAfter("ITEM_SHARED", now.minus(Duration.ofDays(7)));
        long sharedAllTime = telemetryRepository.countByEventType("ITEM_SHARED");
        AdminStatsResponse.LifetimeVolume lifetimeVolume = new AdminStatsResponse.LifetimeVolume(
                sharedToday,
                shared7Days,
                sharedAllTime
        );

        long lifetimeBytes = telemetryRepository.sumPayloadBytes();

        Map<String, Long> popularTtlChoices = new LinkedHashMap<>();
        popularTtlChoices.put("10m", 0L);
        popularTtlChoices.put("30m", 0L);
        popularTtlChoices.put("1h", 0L);
        popularTtlChoices.put("4h", 0L);
        popularTtlChoices.put("24h", 0L);
        popularTtlChoices.put("Custom", 0L);

        for (Object[] row : telemetryRepository.countByTtlSeconds()) {
            if (row != null && row.length == 2 && row[0] != null) {
                int ttl = ((Number) row[0]).intValue();
                long count = ((Number) row[1]).longValue();
                if (ttl <= 600) {
                    popularTtlChoices.merge("10m", count, Long::sum);
                } else if (ttl <= 1800) {
                    popularTtlChoices.merge("30m", count, Long::sum);
                } else if (ttl <= 3600) {
                    popularTtlChoices.merge("1h", count, Long::sum);
                } else if (ttl <= 14400) {
                    popularTtlChoices.merge("4h", count, Long::sum);
                } else if (ttl <= 86400) {
                    popularTtlChoices.merge("24h", count, Long::sum);
                } else {
                    popularTtlChoices.merge("Custom", count, Long::sum);
                }
            }
        }

        long customRooms = sessionRepository.countByIsPersonalFalse();
        long roomMemberships = memberRepository.count();

        AdminStatsResponse.EphemeralStorage ephemeralStorage = new AdminStatsResponse.EphemeralStorage(
                liveItemsTotal,
                liveItemsByType,
                expiringSoon,
                lifetimeVolume,
                lifetimeBytes,
                popularTtlChoices,
                customRooms,
                roomMemberships
        );

        // 5. Server, Disk & JVM Infrastructure Health
        BlobStore.StorageStats blobStats = blobStore.getStorageStats();
        long uptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;

        Runtime rt = Runtime.getRuntime();
        long jvmUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long jvmMaxMb = rt.maxMemory() / (1024 * 1024);

        double cpuLoad = resolveCpuLoad();

        AdminStatsResponse.SystemHealth systemHealth = new AdminStatsResponse.SystemHealth(
                blobStats.fileCount(),
                blobStats.totalBytes(),
                blobStats.freeBytes(),
                blobStats.diskTotalBytes(),
                uptimeSeconds,
                jvmUsedMb,
                jvmMaxMb,
                cpuLoad
        );

        return new AdminStatsResponse(livePulse, recentActivity, usersAndDevices, ephemeralStorage, systemHealth);
    }

    private double resolveCpuLoad() {
        try {
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean) {
                double load = sunOsBean.getCpuLoad();
                if (load < 0) {
                    load = sunOsBean.getProcessCpuLoad();
                }
                if (load >= 0) {
                    return Math.round(load * 1000.0) / 10.0;
                }
            } else {
                double avg = osBean.getSystemLoadAverage();
                if (avg >= 0) {
                    return Math.round(avg * 10.0) / 10.0;
                }
            }
        } catch (Exception ignored) {
        }
        return 0.0;
    }
}

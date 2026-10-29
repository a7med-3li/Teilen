package com.backend.admin.service;

import com.backend.admin.dto.AdminActivityEvent;
import com.backend.admin.entity.TelemetryEvent;
import com.backend.admin.repository.TelemetryEventRepository;
import com.backend.auth.entity.Device;
import com.backend.auth.repository.DeviceRepository;
import com.backend.item.ShareItemType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class TelemetryService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);
    private static final int MAX_BUFFER_SIZE = 50;
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final TelemetryEventRepository telemetryRepository;
    private final DeviceRepository deviceRepository;
    private final ConcurrentLinkedDeque<AdminActivityEvent> activityBuffer = new ConcurrentLinkedDeque<>();
    private final AtomicLong eventSequence = new AtomicLong(1);

    public TelemetryService(TelemetryEventRepository telemetryRepository,
                            DeviceRepository deviceRepository) {
        this.telemetryRepository = telemetryRepository;
        this.deviceRepository = deviceRepository;
    }

    public void recordItemShared(UUID userId, ShareItemType type, long payloadBytes, long ttlSeconds, UUID deviceId) {
        Instant now = Instant.now();
        String platform = resolvePlatform(deviceId);
        String vaultHash = formatVaultHash(userId);
        String timeStr = TIME_FORMATTER.format(now);

        StringBuilder desc = new StringBuilder();
        desc.append(vaultHash).append(" (").append(formatDisplayPlatform(platform)).append(") shared ");
        if (type == ShareItemType.IMAGE || type == ShareItemType.AUDIO) {
            desc.append("an ").append(type.name());
        } else {
            desc.append("a ").append(type.name());
        }
        if (payloadBytes > 0) {
            desc.append(" (").append(formatBytes(payloadBytes)).append(", TTL: ").append(formatTtl(ttlSeconds)).append(")");
        } else {
            desc.append(" (TTL: ").append(formatTtl(ttlSeconds)).append(")");
        }

        AdminActivityEvent event = new AdminActivityEvent(
                eventSequence.getAndIncrement(),
                timeStr,
                vaultHash,
                "ITEM_SHARED",
                desc.toString(),
                type != null ? type.name() : "TEXT",
                payloadBytes,
                (int) ttlSeconds,
                platform
        );
        pushActivity(event);

        try {
            telemetryRepository.save(new TelemetryEvent(
                    "ITEM_SHARED",
                    type != null ? type.name() : "TEXT",
                    payloadBytes,
                    (int) ttlSeconds,
                    platform,
                    now
            ));
        } catch (Exception e) {
            log.warn("Could not persist telemetry event: {}", e.getMessage());
        }
    }

    public void recordDevicePaired(UUID userId, String type1, String type2, int totalUnifiedDevices, String platform) {
        Instant now = Instant.now();
        String vaultHash = formatVaultHash(userId);
        String timeStr = TIME_FORMATTER.format(now);
        String t1 = type1 != null ? type1.toUpperCase(Locale.ROOT) : "PHONE";
        String t2 = type2 != null ? type2.toUpperCase(Locale.ROOT) : "WEB";

        String desc = vaultHash + " paired " + t1 + " + " + t2 + " (" + totalUnifiedDevices + " devices unified)";

        AdminActivityEvent event = new AdminActivityEvent(
                eventSequence.getAndIncrement(),
                timeStr,
                vaultHash,
                "DEVICE_PAIRED",
                desc,
                null,
                0L,
                null,
                normalizePlatform(platform)
        );
        pushActivity(event);

        try {
            telemetryRepository.save(new TelemetryEvent(
                    "DEVICE_PAIRED",
                    null,
                    0L,
                    null,
                    normalizePlatform(platform),
                    now
            ));
        } catch (Exception e) {
            log.warn("Could not persist pairing telemetry: {}", e.getMessage());
        }
    }

    public void recordVaultMerged(UUID primaryUserId, UUID secondaryUserId) {
        Instant now = Instant.now();
        String vaultA = formatVaultHash(primaryUserId);
        String vaultB = formatVaultHash(secondaryUserId);
        String timeStr = TIME_FORMATTER.format(now);

        String desc = vaultA + " merged with " + vaultB + " into unified account";

        AdminActivityEvent event = new AdminActivityEvent(
                eventSequence.getAndIncrement(),
                timeStr,
                vaultA,
                "VAULT_MERGED",
                desc,
                null,
                0L,
                null,
                null
        );
        pushActivity(event);

        try {
            telemetryRepository.save(new TelemetryEvent(
                    "VAULT_MERGED",
                    null,
                    0L,
                    null,
                    null,
                    now
            ));
        } catch (Exception e) {
            log.warn("Could not persist vault merge telemetry: {}", e.getMessage());
        }
    }

    public void recordSessionCreated(UUID userId, String sessionName, int memberCount) {
        Instant now = Instant.now();
        String vaultHash = formatVaultHash(userId);
        String timeStr = TIME_FORMATTER.format(now);

        String desc = vaultHash + " created a shared Session Room (" + memberCount + " members)";

        AdminActivityEvent event = new AdminActivityEvent(
                eventSequence.getAndIncrement(),
                timeStr,
                vaultHash,
                "SESSION_CREATED",
                desc,
                null,
                0L,
                null,
                null
        );
        pushActivity(event);

        try {
            telemetryRepository.save(new TelemetryEvent(
                    "SESSION_CREATED",
                    null,
                    0L,
                    null,
                    null,
                    now
            ));
        } catch (Exception e) {
            log.warn("Could not persist session creation telemetry: {}", e.getMessage());
        }
    }

    public void recordReaperPurged(int itemsCount, long bytesFreed) {
        if (itemsCount <= 0) return;
        Instant now = Instant.now();
        String timeStr = TIME_FORMATTER.format(now);

        String desc = "Reaper purged " + itemsCount + " expired items (" + formatBytes(bytesFreed) + " freed from disk)";

        AdminActivityEvent event = new AdminActivityEvent(
                eventSequence.getAndIncrement(),
                timeStr,
                "SYSTEM",
                "REAPER_PURGED",
                desc,
                null,
                bytesFreed,
                null,
                null
        );
        pushActivity(event);
    }

    public void recordLanP2pCompleted(long payloadBytes, String platform) {
        Instant now = Instant.now();
        String timeStr = TIME_FORMATTER.format(now);

        String desc = "Direct LAN P2P transfer completed (" + formatBytes(payloadBytes) + ")";

        AdminActivityEvent event = new AdminActivityEvent(
                eventSequence.getAndIncrement(),
                timeStr,
                "LAN_PEER",
                "LAN_P2P_COMPLETED",
                desc,
                null,
                payloadBytes,
                null,
                normalizePlatform(platform)
        );
        pushActivity(event);

        try {
            telemetryRepository.save(new TelemetryEvent(
                    "LAN_P2P_COMPLETED",
                    null,
                    payloadBytes,
                    null,
                    normalizePlatform(platform),
                    now
            ));
        } catch (Exception e) {
            log.warn("Could not persist LAN P2P telemetry: {}", e.getMessage());
        }
    }

    public List<AdminActivityEvent> getRecentActivity() {
        if (!activityBuffer.isEmpty()) {
            return new ArrayList<>(activityBuffer);
        }

        // Fallback to recent telemetry events from DB if in-memory buffer is empty
        try {
            List<TelemetryEvent> dbEvents = telemetryRepository.findTop50ByOrderByCreatedAtDesc();
            List<AdminActivityEvent> list = new ArrayList<>(dbEvents.size());
            for (TelemetryEvent e : dbEvents) {
                String timeStr = TIME_FORMATTER.format(e.getCreatedAt());
                String desc = e.getEventType() + " (" + (e.getItemType() != null ? e.getItemType() : "System") + ")";
                list.add(new AdminActivityEvent(
                        e.getId() != null ? e.getId() : 0L,
                        timeStr,
                        "Vault #hist",
                        e.getEventType(),
                        desc,
                        e.getItemType(),
                        e.getPayloadBytes(),
                        e.getTtlSeconds(),
                        e.getPlatform()
                ));
            }
            return list;
        } catch (Exception e) {
            return List.of();
        }
    }

    private void pushActivity(AdminActivityEvent event) {
        activityBuffer.addFirst(event);
        while (activityBuffer.size() > MAX_BUFFER_SIZE) {
            activityBuffer.pollLast();
        }
    }

    private String resolvePlatform(UUID deviceId) {
        if (deviceId != null) {
            try {
                return deviceRepository.findById(deviceId)
                        .map(Device::getPlatform)
                        .map(TelemetryService::normalizePlatform)
                        .orElse("WEB");
            } catch (Exception ignored) {
            }
        }
        return "WEB";
    }

    public static String normalizePlatform(String raw) {
        if (raw == null || raw.isBlank()) return "WEB";
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("android")) return "ANDROID";
        if (lower.contains("iphone") || lower.contains("ipad") || lower.contains("ios")) return "IOS";
        if (lower.contains("mac") || lower.contains("darwin")) return "MACOS";
        if (lower.contains("win")) return "WINDOWS";
        if (lower.contains("linux")) return "LINUX";
        return "WEB";
    }

    public static String formatDisplayPlatform(String platform) {
        if (platform == null) return "Web";
        return switch (platform.toUpperCase(Locale.ROOT)) {
            case "ANDROID" -> "Android";
            case "IOS" -> "iOS";
            case "MACOS" -> "macOS";
            case "WINDOWS" -> "Windows";
            case "LINUX" -> "Linux";
            default -> "Web";
        };
    }

    public static String formatVaultHash(UUID userId) {
        if (userId == null) return "Vault #anon";
        String clean = userId.toString().replace("-", "").toLowerCase(Locale.ROOT);
        return "Vault #" + (clean.length() >= 4 ? clean.substring(0, 4) : clean);
    }

    public static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    public static String formatTtl(long seconds) {
        if (seconds <= 0) return "0s";
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m";
        if (seconds < 86400) {
            long h = seconds / 3600;
            long m = (seconds % 3600) / 60;
            return m > 0 ? h + "h " + m + "m" : h + "h";
        }
        return (seconds / 86400) + "d";
    }
}

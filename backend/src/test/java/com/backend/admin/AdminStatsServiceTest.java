package com.backend.admin;

import com.backend.admin.dto.AdminActivityEvent;
import com.backend.admin.dto.AdminStatsResponse;
import com.backend.admin.repository.TelemetryEventRepository;
import com.backend.admin.service.AdminStatsService;
import com.backend.admin.service.TelemetryService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminStatsServiceTest {

    @Mock
    private ShareItemWebSocketHandler shareItemWebSocketHandler;

    @Mock
    private SignalWebSocketHandler signalWebSocketHandler;

    @Mock
    private UserRepository userRepository;

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private ShareItemRepository shareItemRepository;

    @Mock
    private SessionRepository sessionRepository;

    @Mock
    private SessionMemberRepository memberRepository;

    @Mock
    private TelemetryEventRepository telemetryRepository;

    @Mock
    private BlobStore blobStore;

    @Mock
    private TelemetryService telemetryService;

    private AdminStatsService adminStatsService;

    @BeforeEach
    void setUp() {
        adminStatsService = new AdminStatsService(
                shareItemWebSocketHandler,
                signalWebSocketHandler,
                userRepository,
                deviceRepository,
                shareItemRepository,
                sessionRepository,
                memberRepository,
                telemetryRepository,
                blobStore,
                telemetryService
        );
    }

    @Test
    void getStats_aggregatesAllTelemetrySections() {
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        UUID u3 = UUID.randomUUID();

        when(shareItemWebSocketHandler.getConnectedUserIds()).thenReturn(Set.of(u1, u2));
        when(signalWebSocketHandler.getConnectedUserIds()).thenReturn(Set.of(u2, u3));
        when(shareItemWebSocketHandler.getOpenFeedSocketsCount()).thenReturn(3);
        when(signalWebSocketHandler.getOpenSignalSocketsCount()).thenReturn(2);

        when(deviceRepository.countActiveUsersSince(any(Instant.class))).thenReturn(5L, 12L, 28L);
        when(userRepository.count()).thenReturn(50L);
        when(userRepository.countByUsernameStartingWithIgnoreCase("vault")).thenReturn(35L);
        when(deviceRepository.count()).thenReturn(65L);
        when(deviceRepository.countByDeviceType()).thenReturn(List.of(
                new Object[]{DeviceType.PHONE, 40L},
                new Object[]{DeviceType.WEB, 25L}
        ));
        when(deviceRepository.countByPlatform()).thenReturn(List.of(
                new Object[]{"Android", 35L},
                new Object[]{"Mozilla/5.0 (Windows NT 10.0)", 20L}
        ));
        when(deviceRepository.countMultiDeviceVaults()).thenReturn(14L);

        when(shareItemRepository.countByExpiresAtGreaterThan(any(Instant.class))).thenReturn(42L);
        when(shareItemRepository.countLiveByType(any(Instant.class))).thenReturn(List.of(
                new Object[]{ShareItemType.TEXT, 20L},
                new Object[]{ShareItemType.IMAGE, 15L}
        ));
        when(shareItemRepository.countExpiringSoon(any(Instant.class), any(Instant.class))).thenReturn(4L);

        when(telemetryRepository.countByEventTypeAndCreatedAtAfter(eq("ITEM_SHARED"), any(Instant.class))).thenReturn(100L, 500L);
        when(telemetryRepository.countByEventType("ITEM_SHARED")).thenReturn(2000L);
        when(telemetryRepository.sumPayloadBytes()).thenReturn(104857600L);
        when(telemetryRepository.countByTtlSeconds()).thenReturn(List.of(
                new Object[]{600, 30L},
                new Object[]{3600, 50L}
        ));

        when(sessionRepository.countByIsPersonalFalse()).thenReturn(6L);
        when(memberRepository.count()).thenReturn(18L);

        when(blobStore.getStorageStats()).thenReturn(new BlobStore.StorageStats(15, 25000000L, 50000000000L, 100000000000L));

        when(telemetryService.getRecentActivity()).thenReturn(List.of(
                new AdminActivityEvent(1L, "20:00:00", "Vault #a1b2", "ITEM_SHARED", "Vault #a1b2 shared an IMAGE", "IMAGE", 1024L, 3600, "ANDROID")
        ));

        AdminStatsResponse response = adminStatsService.getStats();

        assertNotNull(response);
        // Live Pulse
        assertEquals(3, response.livePulse().onlineUsersCount()); // Union of {u1, u2} and {u2, u3} = 3
        assertEquals(3, response.livePulse().openFeedSocketsCount());
        assertEquals(2, response.livePulse().openSignalSocketsCount());
        assertEquals(5L, response.livePulse().activeUsersLast5m());

        // Users & Devices
        assertEquals(50L, response.usersAndDevices().totalUsers());
        assertEquals(35L, response.usersAndDevices().anonymousVaultsCount());
        assertEquals(15L, response.usersAndDevices().namedAccountsCount());
        assertEquals(65L, response.usersAndDevices().totalPairedDevices());
        assertEquals(40L, response.usersAndDevices().devicesByType().get("PHONE"));
        assertEquals(14L, response.usersAndDevices().multiDeviceVaultsCount());

        // Ephemeral storage
        assertEquals(42L, response.ephemeralStorage().liveItemsTotal());
        assertEquals(20L, response.ephemeralStorage().liveItemsByType().get("TEXT"));
        assertEquals(104857600L, response.ephemeralStorage().lifetimeBytesTransferred());
        assertEquals(6L, response.ephemeralStorage().totalCustomRooms());

        // System health
        assertEquals(15, response.systemHealth().activeBlobCount());
        assertEquals(25000000L, response.systemHealth().activeBlobBytes());
        assertTrue(response.systemHealth().uptimeSeconds() >= 0);

        // Recent activity
        assertEquals(1, response.recentActivity().size());
        assertEquals("Vault #a1b2", response.recentActivity().get(0).vaultHash());
    }
}

package com.backend.admin;

import com.backend.admin.controller.AdminAuthController;
import com.backend.admin.controller.AdminStatsController;
import com.backend.admin.controller.AdminViewController;
import com.backend.admin.dto.AdminLoginRequest;
import com.backend.admin.dto.AdminLoginResponse;
import com.backend.admin.dto.AdminStatsResponse;
import com.backend.admin.service.AdminAuthService;
import com.backend.admin.service.AdminStatsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminControllersTest {

    @Mock
    private AdminAuthService adminAuthService;

    @Mock
    private AdminStatsService adminStatsService;

    @Mock
    private HttpServletRequest httpServletRequest;

    @Mock
    private HttpServletResponse httpServletResponse;

    private AdminAuthController authController;
    private AdminStatsController statsController;
    private AdminViewController viewController;

    @BeforeEach
    void setUp() {
        authController = new AdminAuthController(adminAuthService);
        statsController = new AdminStatsController(adminStatsService);
        viewController = new AdminViewController();
    }

    @Test
    void loginEndpoint_delegatesToService() {
        AdminLoginResponse mockRes = new AdminLoginResponse("token123", "admin", Instant.now().plusSeconds(3600));
        when(httpServletRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        when(adminAuthService.login(any(AdminLoginRequest.class), eq("127.0.0.1"))).thenReturn(mockRes);

        ResponseEntity<AdminLoginResponse> response = authController.login(
                new AdminLoginRequest("admin", "pass"),
                httpServletRequest,
                httpServletResponse
        );

        assertNotNull(response.getBody());
        assertEquals("token123", response.getBody().adminToken());
        assertEquals("admin", response.getBody().username());
    }

    @Test
    void logoutEndpoint_delegatesToService() {
        ResponseEntity<Map<String, String>> response = authController.logout("my-token", null, httpServletRequest, httpServletResponse);
        verify(adminAuthService).logout("my-token");
        assertEquals("logged_out", response.getBody().get("status"));
    }

    @Test
    void statsEndpoint_returnsAggregatedStats() {
        AdminStatsResponse mockStats = new AdminStatsResponse(
                new AdminStatsResponse.LivePulse(1, 1, 0, 1, 1, 1),
                List.of(),
                new AdminStatsResponse.UsersAndDevices(5, 2, 3, 4, Map.of(), Map.of(), 1),
                new AdminStatsResponse.EphemeralStorage(10, Map.of(), 0, new AdminStatsResponse.LifetimeVolume(1, 2, 3), 100, Map.of(), 0, 0),
                new AdminStatsResponse.SystemHealth(2, 500, 1000, 2000, 300, 50, 100, 1.5)
        );

        when(adminStatsService.getStats()).thenReturn(mockStats);

        ResponseEntity<AdminStatsResponse> response = statsController.getStats();
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().livePulse().onlineUsersCount());
        assertEquals(5, response.getBody().usersAndDevices().totalUsers());
    }

    @Test
    void adminViewRoute_forwardsToAdminHtml() {
        assertEquals("forward:/admin.html", viewController.admin());
    }
}

package com.backend.admin;

import com.backend.admin.dto.AdminLoginRequest;
import com.backend.admin.dto.AdminLoginResponse;
import com.backend.admin.entity.AdminSession;
import com.backend.admin.entity.AdminUser;
import com.backend.admin.repository.AdminSessionRepository;
import com.backend.admin.repository.AdminUserRepository;
import com.backend.admin.security.AdminRateLimiter;
import com.backend.admin.security.AuthenticatedAdmin;
import com.backend.admin.service.AdminAuthService;
import com.backend.auth.domain.Tokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminAuthServiceTest {

    @Mock
    private AdminUserRepository adminUserRepository;

    @Mock
    private AdminSessionRepository adminSessionRepository;

    private PasswordEncoder passwordEncoder;
    private AdminRateLimiter rateLimiter;
    private AdminAuthService adminAuthService;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        rateLimiter = new AdminRateLimiter();
        adminAuthService = new AdminAuthService(
                adminUserRepository,
                adminSessionRepository,
                passwordEncoder,
                rateLimiter
        );
    }

    @Test
    void login_withBcryptPassword_success() {
        UUID adminId = UUID.randomUUID();
        String hash = passwordEncoder.encode("supersecret");
        AdminUser admin = new AdminUser(adminId, "admin", hash, Instant.now(), null);

        when(adminUserRepository.findByUsername("admin")).thenReturn(Optional.of(admin));

        AdminLoginResponse response = adminAuthService.login(
                new AdminLoginRequest("admin", "supersecret"),
                "127.0.0.1"
        );

        assertNotNull(response);
        assertNotNull(response.adminToken());
        assertEquals("admin", response.username());
        assertNotNull(response.expiresAt());
        verify(adminSessionRepository).save(any(AdminSession.class));
        verify(adminUserRepository).save(admin);
        assertNotNull(admin.getLastLoginAt());
    }

    @Test
    void login_withSha256PrefixedPassword_success() {
        UUID adminId = UUID.randomUUID();
        String shaHex = Tokens.hash("mypassword");
        AdminUser admin = new AdminUser(adminId, "admin2", "{sha256}" + shaHex, Instant.now(), null);

        when(adminUserRepository.findByUsername("admin2")).thenReturn(Optional.of(admin));

        AdminLoginResponse response = adminAuthService.login(
                new AdminLoginRequest("admin2", "mypassword"),
                "127.0.0.2"
        );

        assertNotNull(response);
        assertEquals("admin2", response.username());
    }

    @Test
    void login_withWrongPassword_fails() {
        UUID adminId = UUID.randomUUID();
        String hash = passwordEncoder.encode("correct");
        AdminUser admin = new AdminUser(adminId, "admin", hash, Instant.now(), null);

        when(adminUserRepository.findByUsername("admin")).thenReturn(Optional.of(admin));

        assertThrows(ResponseStatusException.class, () ->
                adminAuthService.login(new AdminLoginRequest("admin", "wrong"), "127.0.0.3")
        );
    }

    @Test
    void login_unknownUser_fails() {
        when(adminUserRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class, () ->
                adminAuthService.login(new AdminLoginRequest("ghost", "pass"), "127.0.0.4")
        );
    }

    @Test
    void rateLimiter_enforcesMax5FailedAttempts() {
        when(adminUserRepository.findByUsername("victim")).thenReturn(Optional.empty());

        String ip = "192.168.1.100";
        for (int i = 0; i < 5; i++) {
            assertThrows(ResponseStatusException.class, () ->
                    adminAuthService.login(new AdminLoginRequest("victim", "wrong"), ip)
            );
        }

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                adminAuthService.login(new AdminLoginRequest("victim", "wrong"), ip)
        );
        assertEquals(429, ex.getStatusCode().value());
    }

    @Test
    void authenticate_validToken_returnsAdmin() {
        UUID adminId = UUID.randomUUID();
        String rawToken = Tokens.newToken();
        String tokenHash = Tokens.hash(rawToken);

        AdminSession session = new AdminSession(
                UUID.randomUUID(),
                adminId,
                tokenHash,
                Instant.now().plusSeconds(3600),
                Instant.now()
        );
        AdminUser user = new AdminUser(adminId, "admin", "hash", Instant.now(), null);

        when(adminSessionRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(session));
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(user));

        AuthenticatedAdmin admin = adminAuthService.authenticate(rawToken);
        assertNotNull(admin);
        assertEquals(adminId, admin.adminId());
        assertEquals("admin", admin.username());
    }

    @Test
    void authenticate_expiredToken_returnsNull() {
        UUID adminId = UUID.randomUUID();
        String rawToken = Tokens.newToken();
        String tokenHash = Tokens.hash(rawToken);

        AdminSession session = new AdminSession(
                UUID.randomUUID(),
                adminId,
                tokenHash,
                Instant.now().minusSeconds(10), // expired
                Instant.now().minusSeconds(3600)
        );

        when(adminSessionRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(session));

        AuthenticatedAdmin admin = adminAuthService.authenticate(rawToken);
        assertNull(admin);
    }
}

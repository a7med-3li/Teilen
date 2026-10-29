package com.backend.admin.service;

import com.backend.admin.dto.AdminLoginRequest;
import com.backend.admin.dto.AdminLoginResponse;
import com.backend.admin.entity.AdminSession;
import com.backend.admin.entity.AdminUser;
import com.backend.admin.repository.AdminSessionRepository;
import com.backend.admin.repository.AdminUserRepository;
import com.backend.admin.security.AdminRateLimiter;
import com.backend.admin.security.AuthenticatedAdmin;
import com.backend.auth.domain.Tokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class AdminAuthService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthService.class);
    private static final Duration SESSION_DURATION = Duration.ofHours(12);

    private final AdminUserRepository adminUsers;
    private final AdminSessionRepository adminSessions;
    private final PasswordEncoder passwordEncoder;
    private final AdminRateLimiter rateLimiter;

    public AdminAuthService(AdminUserRepository adminUsers,
                            AdminSessionRepository adminSessions,
                            PasswordEncoder passwordEncoder,
                            AdminRateLimiter rateLimiter) {
        this.adminUsers = adminUsers;
        this.adminSessions = adminSessions;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
    }

    @Transactional
    public AdminLoginResponse login(AdminLoginRequest request, String ipAddress) {
        String rateKey = "admin_login:" + (ipAddress != null ? ipAddress : "unknown");
        rateLimiter.checkAllowed(rateKey);

        AdminUser admin = adminUsers.findByUsername(request.username().strip())
                .orElse(null);

        if (admin == null) {
            rateLimiter.recordFailure(rateKey);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_credentials");
        }

        boolean matches = checkPassword(request.password(), admin.getPasswordHash());
        if (!matches) {
            rateLimiter.recordFailure(rateKey);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_credentials");
        }

        rateLimiter.recordSuccess(rateKey);
        String adminToken = Tokens.newToken();
        String tokenHash = Tokens.hash(adminToken);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(SESSION_DURATION);

        AdminSession session = new AdminSession(UUID.randomUUID(), admin.getId(), tokenHash, expiresAt, now);
        adminSessions.save(session);

        admin.setLastLoginAt(now);
        adminUsers.save(admin);

        log.info("Admin user '{}' signed in successfully from {}", admin.getUsername(), ipAddress);
        return new AdminLoginResponse(adminToken, admin.getUsername(), expiresAt);
    }

    @Transactional
    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        String tokenHash = Tokens.hash(rawToken);
        adminSessions.deleteByTokenHash(tokenHash);
    }

    @Transactional(readOnly = true)
    public AuthenticatedAdmin authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        String tokenHash = Tokens.hash(rawToken);
        AdminSession session = adminSessions.findByTokenHash(tokenHash).orElse(null);
        if (session == null || session.isExpired(Instant.now())) {
            return null;
        }
        AdminUser admin = adminUsers.findById(session.getAdminId()).orElse(null);
        if (admin == null) {
            return null;
        }
        return new AuthenticatedAdmin(admin.getId(), admin.getUsername());
    }

    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void sweepExpiredSessions() {
        long purged = adminSessions.deleteByExpiresAtBefore(Instant.now());
        if (purged > 0) {
            log.debug("Swept {} expired admin session(s)", purged);
        }
    }

    private boolean checkPassword(String rawPassword, String storedHash) {
        if (rawPassword == null || storedHash == null) {
            return false;
        }
        // 1. Support {sha256} prefix for easy manual SQL insertion
        if (storedHash.startsWith("{sha256}") || storedHash.startsWith("{SHA256}")) {
            String expectedHex = storedHash.substring(8).strip();
            String rawHex = Tokens.hash(rawPassword);
            return MessageDigest.isEqual(expectedHex.toLowerCase().getBytes(StandardCharsets.UTF_8),
                    rawHex.toLowerCase().getBytes(StandardCharsets.UTF_8));
        }
        // 2. Support raw 64-character SHA-256 hex string
        if (storedHash.matches("^[a-fA-F0-9]{64}$")) {
            String rawHex = Tokens.hash(rawPassword);
            return MessageDigest.isEqual(storedHash.toLowerCase().getBytes(StandardCharsets.UTF_8),
                    rawHex.toLowerCase().getBytes(StandardCharsets.UTF_8));
        }
        // 3. Standard BCrypt ($2a$, $2b$, $2y$)
        try {
            return passwordEncoder.matches(rawPassword, storedHash);
        } catch (Exception e) {
            log.warn("BCrypt matching exception: {}", e.getMessage());
            return false;
        }
    }
}

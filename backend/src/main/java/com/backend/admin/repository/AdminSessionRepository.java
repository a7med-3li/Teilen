package com.backend.admin.repository;

import com.backend.admin.entity.AdminSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AdminSessionRepository extends JpaRepository<AdminSession, UUID> {
    Optional<AdminSession> findByTokenHash(String tokenHash);
    void deleteByTokenHash(String tokenHash);
    long deleteByExpiresAtBefore(Instant now);
}

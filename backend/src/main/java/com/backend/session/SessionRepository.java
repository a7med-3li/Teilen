package com.backend.session;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionRepository extends JpaRepository<Session, UUID> {
    Optional<Session> findByInviteCodeHash(String inviteCodeHash);
    Optional<Session> findByOwnerUserIdAndIsPersonalTrue(UUID ownerUserId);
    List<Session> findByOwnerUserId(UUID ownerUserId);
    long countByIsPersonalFalse();
}

package com.backend.session;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionMemberRepository extends JpaRepository<SessionMember, UUID> {
    List<SessionMember> findByUserId(UUID userId);
    List<SessionMember> findBySessionId(UUID sessionId);
    Optional<SessionMember> findBySessionIdAndUserId(UUID sessionId, UUID userId);
    boolean existsBySessionIdAndUserId(UUID sessionId, UUID userId);
    void deleteBySessionIdAndUserId(UUID sessionId, UUID userId);
    void deleteBySessionId(UUID sessionId);
    long countBySessionId(UUID sessionId);
}

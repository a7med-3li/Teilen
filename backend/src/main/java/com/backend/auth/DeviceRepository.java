package com.backend.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceRepository extends JpaRepository<Device, UUID> {

    Optional<Device> findByTokenHash(String tokenHash);

    List<Device> findByUserIdOrderByCreatedAtAsc(UUID userId);

    /** "is this the last way in?" — the one question self-revocation turns on */
    long countByUserIdAndIdNot(UUID userId, UUID id);

    void deleteByIdAndUserId(UUID id, UUID userId);

    /**
     * One write per device every few minutes instead of one per request: the feed polls and pushes
     * constantly and "last seen" is only a rough hint anyway.
     */
    @Modifying
    @Query("update Device d set d.lastSeenAt = :now"
            + " where d.id = :id and d.lastSeenAt < :cutoff")
    int touch(@Param("id") UUID id, @Param("now") Instant now, @Param("cutoff") Instant cutoff);
}

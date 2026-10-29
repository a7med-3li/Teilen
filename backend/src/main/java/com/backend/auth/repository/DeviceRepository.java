package com.backend.auth.repository;

import com.backend.auth.entity.Device;
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

    Optional<Device> findFirstByUserIdAndClientFingerprint(UUID userId, String clientFingerprint);

    Optional<Device> findFirstByUserIdAndNameAndDeviceType(UUID userId, String name, com.backend.auth.enums.DeviceType deviceType);

    List<Device> findByUserIdAndNameAndDeviceType(UUID userId, String name, com.backend.auth.enums.DeviceType deviceType);

    /** "is this the last way in?" — the one question self-revocation turns on */
    long countByUserIdAndIdNot(UUID userId, UUID id);

    void deleteByIdAndUserId(UUID id, UUID userId);

    @Modifying
    @Query("delete from Device d where d.userId = :userId and d.id <> :id")
    void deleteByUserIdAndIdNot(@Param("userId") UUID userId, @Param("id") UUID id);

    /**
     * One write per device every few minutes instead of one per request: the feed polls and pushes
     * constantly and "last seen" is only a rough hint anyway.
     */
    @Modifying
    @Query("update Device d set d.lastSeenAt = :now"
            + " where d.id = :id and d.lastSeenAt < :cutoff")
    int touch(@Param("id") UUID id, @Param("now") Instant now, @Param("cutoff") Instant cutoff);

    @Query("SELECT COUNT(DISTINCT d.userId) FROM Device d WHERE d.lastSeenAt >= :since")
    long countActiveUsersSince(@Param("since") Instant since);

    @Query("SELECT d.deviceType, COUNT(d) FROM Device d GROUP BY d.deviceType")
    List<Object[]> countByDeviceType();

    @Query("SELECT d.platform, COUNT(d) FROM Device d GROUP BY d.platform")
    List<Object[]> countByPlatform();

    @Query(value = "SELECT COUNT(*) FROM (SELECT user_id FROM devices GROUP BY user_id HAVING COUNT(*) >= 2) t", nativeQuery = true)
    long countMultiDeviceVaults();
}

package com.backend.item;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShareItemRepository extends JpaRepository<ShareItem, UUID> {

    /** one feed: this account's items that have not run out yet */
    List<ShareItem> findByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(UUID userId, Instant now);

    /** scoped feed: items belonging to a particular session */
    List<ShareItem> findBySessionIdAndExpiresAtAfterOrderByCreatedAtDesc(UUID sessionId, Instant now);

    List<ShareItem> findBySessionId(UUID sessionId);

    void deleteBySessionId(UUID sessionId);

    /** personal feed: items belonging to user where sessionId is null OR sessionId equals personal session */
    @Query("SELECT s FROM ShareItem s WHERE s.userId = :userId AND (s.sessionId IS NULL OR s.sessionId = :personalSessionId) AND s.expiresAt > :now ORDER BY s.createdAt DESC")
    List<ShareItem> findPersonalItems(@Param("userId") UUID userId, @Param("personalSessionId") UUID personalSessionId, @Param("now") Instant now);

    /** multiple sessions: items belonging to any session the user is member of */
    List<ShareItem> findBySessionIdInAndExpiresAtAfterOrderByCreatedAtDesc(List<UUID> sessionIds, Instant now);

    Optional<ShareItem> findByIdAndUserId(UUID id, UUID userId);

    /** the sweep looks at everyone at once; broadcasting is sorted out per owner afterwards */
    List<ShareItem> findByExpiresAtLessThanEqual(Instant now);

    long countByExpiresAtGreaterThan(Instant now);

    @Query("SELECT s.type, COUNT(s) FROM ShareItem s WHERE s.expiresAt > :now GROUP BY s.type")
    List<Object[]> countLiveByType(@Param("now") Instant now);

    @Query("SELECT COUNT(s) FROM ShareItem s WHERE s.expiresAt > :now AND s.expiresAt <= :soon")
    long countExpiringSoon(@Param("now") Instant now, @Param("soon") Instant soon);
}

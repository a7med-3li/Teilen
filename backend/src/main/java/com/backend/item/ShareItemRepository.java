package com.backend.item;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShareItemRepository extends JpaRepository<ShareItem, UUID> {

    /** one feed: this account's items that have not run out yet */
    List<ShareItem> findByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(UUID userId, Instant now);

    /** scoped feed: items belonging to a particular session */
    List<ShareItem> findBySessionIdAndExpiresAtAfterOrderByCreatedAtDesc(UUID sessionId, Instant now);

    /** multiple sessions: items belonging to any session the user is member of */
    List<ShareItem> findBySessionIdInAndExpiresAtAfterOrderByCreatedAtDesc(List<UUID> sessionIds, Instant now);

    Optional<ShareItem> findByIdAndUserId(UUID id, UUID userId);

    /** the sweep looks at everyone at once; broadcasting is sorted out per owner afterwards */
    List<ShareItem> findByExpiresAtLessThanEqual(Instant now);
}

package com.backend.item;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ShareItemRepository extends JpaRepository<ShareItem, UUID> {

    List<ShareItem> findByExpiresAtAfterOrderByCreatedAtDesc(Instant now);

    List<ShareItem> findByExpiresAtLessThanEqual(Instant now);
}

package com.backend.push;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, UUID> {

    List<PushSubscription> findByUserId(UUID userId);

    Optional<PushSubscription> findByUserIdAndEndpoint(UUID userId, String endpoint);

    /** called off the request thread when a push service says a browser is gone */
    @Transactional
    void deleteByUserIdAndEndpoint(UUID userId, String endpoint);
}

package com.backend.auth.service;

import com.backend.auth.entity.User;
import com.backend.auth.repository.UserRepository;
import com.backend.item.ShareItemEvent;
import com.backend.item.ShareItemWebSocketHandler;
import com.backend.session.Session;
import com.backend.session.SessionService;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class VaultMergeService {

    private static final Logger log = LoggerFactory.getLogger(VaultMergeService.class);

    private final EntityManager entityManager;
    private final UserRepository userRepository;
    private final SessionService sessionService;
    private final ShareItemWebSocketHandler webSocketHandler;

    public VaultMergeService(EntityManager entityManager,
                             UserRepository userRepository,
                             SessionService sessionService,
                             ShareItemWebSocketHandler webSocketHandler) {
        this.entityManager = entityManager;
        this.userRepository = userRepository;
        this.sessionService = sessionService;
        this.webSocketHandler = webSocketHandler;
    }

    public static boolean isAnonymous(User u) {
        if (u == null || u.getUsername() == null) return true;
        String name = u.getUsername().toLowerCase();
        return name.startsWith("vault") && name.length() >= 8;
    }

    public record MergeResult(UUID primaryUserId, UUID secondaryUserId, boolean merged) {}

    @Transactional
    public MergeResult mergeIfDifferent(UUID userAId, UUID userBId, boolean isAHost) {
        if (userAId == null || userBId == null || userAId.equals(userBId)) {
            return new MergeResult(userAId != null ? userAId : userBId, null, false);
        }

        User userA = userRepository.findById(userAId).orElse(null);
        User userB = userRepository.findById(userBId).orElse(null);

        if (userA == null || userB == null) {
            return new MergeResult(userA != null ? userAId : userBId, null, false);
        }

        boolean aNamed = !isAnonymous(userA);
        boolean bNamed = !isAnonymous(userB);

        UUID primaryUserId;
        UUID secondaryUserId;

        if (aNamed && !bNamed) {
            primaryUserId = userAId;
            secondaryUserId = userBId;
        } else if (!aNamed && bNamed) {
            primaryUserId = userBId;
            secondaryUserId = userAId;
        } else {
            // Both named or both anonymous: Host takes precedence if isAHost, else older user
            if (isAHost) {
                primaryUserId = userAId;
                secondaryUserId = userBId;
            } else {
                primaryUserId = userB.getCreatedAt().isBefore(userA.getCreatedAt()) ? userBId : userAId;
                secondaryUserId = primaryUserId.equals(userAId) ? userBId : userAId;
            }
        }

        log.info("Merging secondary vault {} into primary vault {}", secondaryUserId, primaryUserId);

        // Ensure primary user has a personal session
        Session primaryPersonalSession = sessionService.getOrCreatePersonalSession(primaryUserId);
        UUID primaryPersonalSessionId = primaryPersonalSession.getId();

        // 1. Clear pairing_requests
        try {
            entityManager.createNativeQuery("""
                DELETE FROM pairing_requests WHERE user_id = :secondaryUserId
            """)
            .setParameter("secondaryUserId", secondaryUserId)
            .executeUpdate();
        } catch (Exception ignored) {
        }

        // 2. Reassign share_items + session_id NULL
        entityManager.createNativeQuery("""
            UPDATE share_items
            SET user_id = :primaryUserId,
                session_id = CASE
                    WHEN session_id IN (SELECT id FROM sessions WHERE owner_user_id = :secondaryUserId AND is_personal = true)
                    THEN :primaryPersonalSessionId
                    ELSE COALESCE(session_id, :primaryPersonalSessionId)
                END
            WHERE user_id = :secondaryUserId
        """)
        .setParameter("primaryUserId", primaryUserId)
        .setParameter("secondaryUserId", secondaryUserId)
        .setParameter("primaryPersonalSessionId", primaryPersonalSessionId)
        .executeUpdate();

        // Also ensure any share_items pointing to secondary that might need reassignment
        entityManager.createNativeQuery("""
            UPDATE share_items
            SET user_id = :primaryUserId,
                session_id = COALESCE(session_id, :primaryPersonalSessionId)
            WHERE user_id = :secondaryUserId
        """)
        .setParameter("primaryUserId", primaryUserId)
        .setParameter("secondaryUserId", secondaryUserId)
        .setParameter("primaryPersonalSessionId", primaryPersonalSessionId)
        .executeUpdate();

        // 3. Reassign non-personal sessions and delete secondary personal session
        entityManager.createNativeQuery("""
            UPDATE sessions
            SET owner_user_id = :primaryUserId
            WHERE owner_user_id = :secondaryUserId AND is_personal = false
        """)
        .setParameter("primaryUserId", primaryUserId)
        .setParameter("secondaryUserId", secondaryUserId)
        .executeUpdate();

        entityManager.createNativeQuery("""
            DELETE FROM sessions WHERE owner_user_id = :secondaryUserId AND is_personal = true
        """)
        .setParameter("secondaryUserId", secondaryUserId)
        .executeUpdate();

        // 4. Dedupe/reassign session_members
        entityManager.createNativeQuery("""
            UPDATE session_members
            SET user_id = :primaryUserId
            WHERE user_id = :secondaryUserId
              AND session_id NOT IN (SELECT session_id FROM session_members WHERE user_id = :primaryUserId)
        """)
        .setParameter("primaryUserId", primaryUserId)
        .setParameter("secondaryUserId", secondaryUserId)
        .executeUpdate();

        entityManager.createNativeQuery("""
            DELETE FROM session_members WHERE user_id = :secondaryUserId
        """)
        .setParameter("secondaryUserId", secondaryUserId)
        .executeUpdate();

        // 5. Move devices
        entityManager.createNativeQuery("""
            UPDATE devices
            SET user_id = :primaryUserId
            WHERE user_id = :secondaryUserId
        """)
        .setParameter("primaryUserId", primaryUserId)
        .setParameter("secondaryUserId", secondaryUserId)
        .executeUpdate();

        // 6. Dedupe devices keeping tokenHash match (prefer to keep devices matching by token_hash if present; else dedupe by fingerprint/name+type keeping most recent)
        try {
            entityManager.createNativeQuery("""
                DELETE FROM devices d2
                USING devices d1
                WHERE d1.user_id = :primaryUserId
                  AND d2.user_id = :primaryUserId
                  AND d1.id <> d2.id
                  AND d1.token_hash = d2.token_hash
                  AND d1.created_at >= d2.created_at
            """)
            .setParameter("primaryUserId", primaryUserId)
            .executeUpdate();
        } catch (Exception ignored) {
        }

        entityManager.createNativeQuery("""
            DELETE FROM devices d2
            USING devices d1
            WHERE d1.user_id = :primaryUserId
              AND d2.user_id = :primaryUserId
              AND d1.id <> d2.id
              AND (
                (d1.client_fingerprint IS NOT NULL AND d1.client_fingerprint <> '' AND d1.client_fingerprint = d2.client_fingerprint)
                OR (d1.name = d2.name AND d1.device_type = d2.device_type)
              )
              AND d1.last_seen_at >= d2.last_seen_at
        """)
        .setParameter("primaryUserId", primaryUserId)
        .executeUpdate();

        // 7. Dedupe/reassign push_subscriptions
        entityManager.createNativeQuery("""
            DELETE FROM push_subscriptions
            WHERE user_id = :secondaryUserId
              AND endpoint IN (SELECT endpoint FROM push_subscriptions WHERE user_id = :primaryUserId)
        """)
        .setParameter("primaryUserId", primaryUserId)
        .setParameter("secondaryUserId", secondaryUserId)
        .executeUpdate();

        entityManager.createNativeQuery("""
            UPDATE push_subscriptions
            SET user_id = :primaryUserId
            WHERE user_id = :secondaryUserId
        """)
        .setParameter("primaryUserId", primaryUserId)
        .setParameter("secondaryUserId", secondaryUserId)
        .executeUpdate();

        // 8. Delete user row
        entityManager.createNativeQuery("""
            DELETE FROM users WHERE id = :secondaryUserId
        """)
        .setParameter("secondaryUserId", secondaryUserId)
        .executeUpdate();

        // 8. Broadcast real-time WebSocket event to all connected sessions of primary and secondary
        ShareItemEvent syncEvent = ShareItemEvent.syncAll();
        webSocketHandler.broadcast(primaryUserId, syncEvent);
        webSocketHandler.broadcast(secondaryUserId, syncEvent);

        log.info("Vault merge complete: {} is now unified into {}", secondaryUserId, primaryUserId);
        return new MergeResult(primaryUserId, secondaryUserId, true);
    }
}

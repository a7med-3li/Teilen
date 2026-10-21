package com.backend.item;

import com.backend.auth.domain.AuthenticatedUser;
import com.backend.push.PushService;
import com.backend.session.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Everything about a shared item, scoped to a Session (Personal or Shared Room).
 */
@Service
public class ShareItemService {

    private static final Logger log = LoggerFactory.getLogger(ShareItemService.class);

    /** a file plus where it landed on disk */
    public record Blob(Resource resource, String mimeType, String fileName) {
    }

    private final ShareItemRepository repository;
    private final ShareItemWebSocketHandler webSocketHandler;
    private final BlobStore blobStore;
    private final BlobLinks blobLinks;
    private final PushService pushService;
    private final SessionService sessionService;

    private final long defaultTtlSeconds;
    private final long minTtlSeconds;
    private final long maxTtlSeconds;

    public ShareItemService(ShareItemRepository repository,
                            ShareItemWebSocketHandler webSocketHandler,
                            BlobStore blobStore,
                            BlobLinks blobLinks,
                            PushService pushService,
                            SessionService sessionService,
                            @Value("${teilen.default-ttl-seconds:3600}") long defaultTtlSeconds,
                            @Value("${teilen.min-ttl-seconds:10}") long minTtlSeconds,
                            @Value("${teilen.max-ttl-seconds:86400}") long maxTtlSeconds) {
        this.repository = repository;
        this.webSocketHandler = webSocketHandler;
        this.blobStore = blobStore;
        this.blobLinks = blobLinks;
        this.pushService = pushService;
        this.sessionService = sessionService;
        this.defaultTtlSeconds = defaultTtlSeconds;
        this.minTtlSeconds = minTtlSeconds;
        this.maxTtlSeconds = maxTtlSeconds;
    }

    @Transactional
    public ShareItemResponse create(UUID userId, CreateShareItemRequest request) {
        String content = request.content().strip();
        ShareItemType type = resolveType(request.type(), content);
        Duration ttl = resolveTtl(request.ttlSeconds());
        UUID sessionId = resolveSessionId(userId, request.sessionId());

        Instant now = Instant.now();
        ShareItem item = new ShareItem(UUID.randomUUID(), userId, sessionId, type, content, now, now.plus(ttl));
        repository.save(item);

        log.info("{} created {} item {} in session {} (expires in {}s)", userId, type, item.getId(), sessionId, ttl.toSeconds());
        broadcastToSession(sessionId, userId, ShareItemEvent.created(item, null));
        return ShareItemResponse.from(item, null);
    }

    @Transactional
    public ShareItemResponse upload(UUID userId, MultipartFile file, Long requestedTtl) {
        return upload(userId, file, requestedTtl, null);
    }

    @Transactional
    public ShareItemResponse upload(UUID userId, MultipartFile file, Long requestedTtl, UUID requestedSessionId) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "no file in the upload");
        }
        String fileName = fileNameOf(file.getOriginalFilename());
        UUID sessionId = resolveSessionId(userId, requestedSessionId);

        UUID id = UUID.randomUUID();
        BlobStore.Stored stored;
        try (InputStream in = file.getInputStream()) {
            stored = blobStore.store(id, in);
        } catch (IOException e) {
            log.warn("upload of {} failed", fileName, e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "could not read the upload");
        }

        ShareItemType type = ShareItemType.fromMime(file.getContentType(), fileName);
        Duration ttl = resolveTtl(requestedTtl);

        Instant now = Instant.now();
        ShareItem item = new ShareItem(id, userId, sessionId, type, fileName, stored.storageRef(),
                file.getContentType(), stored.sizeBytes(), now, now.plus(ttl));
        repository.save(item);

        log.info("{} uploaded {} item {} in session {} ({} bytes, expires in {}s)", userId, type, id,
                sessionId, stored.sizeBytes(), ttl.toSeconds());
        String blobUrl = blobLinks.linkFor(id, item.getExpiresAt());
        broadcastToSession(sessionId, userId, ShareItemEvent.created(item, blobUrl));
        return ShareItemResponse.from(item, blobUrl);
    }

    @Transactional(readOnly = true)
    public List<ShareItemResponse> listLive(UUID userId) {
        return listLive(userId, null);
    }

    @Transactional(readOnly = true)
    public List<ShareItemResponse> listLive(UUID userId, UUID sessionId) {
        Instant now = Instant.now();
        List<ShareItem> items;
        if (sessionId != null) {
            if (!sessionService.isMember(sessionId, userId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of session " + sessionId);
            }
            items = repository.findBySessionIdAndExpiresAtAfterOrderByCreatedAtDesc(sessionId, now);
        } else {
            UUID personalSessionId = sessionService.getOrCreatePersonalSession(userId).getId();
            items = repository.findBySessionIdAndExpiresAtAfterOrderByCreatedAtDesc(personalSessionId, now);
        }
        return items.stream()
                .map(this::response)
                .toList();
    }

    @Transactional(readOnly = true)
    public Blob blob(UUID id, String linkToken, AuthenticatedUser caller) {
        ShareItem item = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such item: " + id));
        boolean member = caller != null && (caller.userId().equals(item.getUserId())
                || sessionService.isMember(item.getSessionId(), caller.userId()));
        if (caller != null && !member) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such item: " + id);
        }
        if (!member && !blobLinks.isValid(id, linkToken)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such item: " + id);
        }
        if (!item.hasBlob() || !blobStore.exists(item.getStorageRef())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no file for item: " + id);
        }
        return new Blob(new FileSystemResource(blobStore.resolve(item.getStorageRef())),
                item.getMimeType() == null ? "application/octet-stream" : item.getMimeType(),
                item.getContent());
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        ShareItem item = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such item: " + id));

        boolean canDelete = item.getUserId().equals(userId)
                || sessionService.isMember(item.getSessionId(), userId);
        if (!canDelete) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such item: " + id);
        }

        repository.deleteById(id);
        log.info("{} deleted item {} from session {}", userId, id, item.getSessionId());
        afterCommit(() -> dropBlob(item.getStorageRef()));
        broadcastToSession(item.getSessionId(), userId, ShareItemEvent.deleted(id));
    }

    @Transactional
    public ShareItemResponse extend(UUID userId, UUID id, Long requestedSeconds) {
        ShareItem item = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such item: " + id));

        boolean canExtend = item.getUserId().equals(userId)
                || sessionService.isMember(item.getSessionId(), userId);
        if (!canExtend) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such item: " + id);
        }

        Duration ttl = resolveTtl(requestedSeconds);
        item.extendTo(Instant.now().plus(ttl));
        repository.save(item);
        log.info("{} extended item {} by {}s in session {}", userId, id, ttl.toSeconds(), item.getSessionId());
        ShareItemResponse response = response(item);
        broadcastToSession(item.getSessionId(), userId, ShareItemEvent.extended(item, response.blobUrl()));
        return response;
    }

    @Scheduled(fixedDelayString = "${teilen.reap-interval-ms:5000}")
    @Transactional
    public void reapExpired() {
        List<ShareItem> expired = repository.findByExpiresAtLessThanEqual(Instant.now());
        if (expired.isEmpty()) {
            return;
        }
        repository.deleteAll(expired);
        log.info("reaped {} expired item(s)", expired.size());
        expired.forEach(item -> {
            afterCommit(() -> dropBlob(item.getStorageRef()));
            List<UUID> targets = sessionService.getMemberUserIds(item.getSessionId());
            if (targets.isEmpty()) {
                targets = List.of(item.getUserId());
            }
            List<UUID> finalTargets = targets;
            afterCommit(() -> webSocketHandler.broadcastToUsers(finalTargets,
                    ShareItemEvent.deleted(item.getId())));
        });
    }

    private UUID resolveSessionId(UUID userId, UUID requestedSessionId) {
        if (requestedSessionId != null) {
            if (!sessionService.isMember(requestedSessionId, userId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of session " + requestedSessionId);
            }
            return requestedSessionId;
        }
        return sessionService.getOrCreatePersonalSession(userId).getId();
    }

    private void broadcastToSession(UUID sessionId, UUID authorId, ShareItemEvent event) {
        List<UUID> memberIds = sessionService.getMemberUserIds(sessionId);
        if (memberIds.isEmpty()) {
            memberIds = List.of(authorId);
        }
        List<UUID> targets = memberIds;
        afterCommit(() -> webSocketHandler.broadcastToUsers(targets, event));
        for (UUID mId : targets) {
            afterCommit(() -> pushService.notifyAvailable(mId));
        }
    }

    private ShareItemResponse response(ShareItem item) {
        return ShareItemResponse.from(item, blobLinks.linkFor(item.getId(), item.getExpiresAt()));
    }

    private void dropBlob(String storageRef) {
        if (storageRef != null) {
            blobStore.delete(storageRef);
        }
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private ShareItemType resolveType(String requested, String content) {
        if (requested == null || requested.isBlank()) {
            return ShareItemType.detect(content);
        }
        try {
            return ShareItemType.valueOf(requested.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "unknown type '" + requested + "', expected one of TEXT, LINK");
        }
    }

    private Duration resolveTtl(Long requestedSeconds) {
        long seconds = requestedSeconds == null ? defaultTtlSeconds : requestedSeconds;
        seconds = Math.clamp(seconds, minTtlSeconds, maxTtlSeconds);
        return Duration.ofSeconds(seconds);
    }

    private String fileNameOf(String original) {
        if (original == null || original.isBlank()) {
            return "shared-file";
        }
        String cleaned = original.replaceAll("[/\\\\\\r\\n\"]", "_").strip();
        return cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned;
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void cleanupOrphanedBlobsOnStartup() {
        try {
            java.util.Set<String> activeRefs = repository.findAll().stream()
                    .map(ShareItem::getStorageRef)
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            blobStore.deleteOrphansExcept(activeRefs);
            log.info("completed startup cleanup of orphaned blobs");
        } catch (Exception e) {
            log.warn("failed to sweep orphaned blobs on startup", e);
        }
    }
}

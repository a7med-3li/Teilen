package com.backend.item;

import com.backend.auth.domain.AuthenticatedUser;
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
 * Everything about a shared item, always in the context of the account that owns it: the owner is
 * passed in, never looked up, so there is no way to read somebody else's feed by forgetting a
 * filter.
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

    private final long defaultTtlSeconds;
    private final long minTtlSeconds;
    private final long maxTtlSeconds;

    public ShareItemService(ShareItemRepository repository,
                            ShareItemWebSocketHandler webSocketHandler,
                            BlobStore blobStore,
                            BlobLinks blobLinks,
                            @Value("${teilen.default-ttl-seconds:1800}") long defaultTtlSeconds,
                            @Value("${teilen.min-ttl-seconds:10}") long minTtlSeconds,
                            @Value("${teilen.max-ttl-seconds:86400}") long maxTtlSeconds) {
        this.repository = repository;
        this.webSocketHandler = webSocketHandler;
        this.blobStore = blobStore;
        this.blobLinks = blobLinks;
        this.defaultTtlSeconds = defaultTtlSeconds;
        this.minTtlSeconds = minTtlSeconds;
        this.maxTtlSeconds = maxTtlSeconds;
    }

    @Transactional
    public ShareItemResponse create(UUID userId, CreateShareItemRequest request) {
        String content = request.content().strip();
        ShareItemType type = resolveType(request.type(), content);
        Duration ttl = resolveTtl(request.ttlSeconds());

        Instant now = Instant.now();
        ShareItem item = new ShareItem(UUID.randomUUID(), userId, type, content, now, now.plus(ttl));
        repository.save(item);

        log.info("{} created {} item {} (expires in {}s)", userId, type, item.getId(), ttl.toSeconds());
        // no blob on a text item, so there is nothing to sign
        afterCommit(() -> webSocketHandler.broadcast(userId, ShareItemEvent.created(item, null)));
        return ShareItemResponse.from(item, null);
    }

    /**
     * A shared file. The blob is written before the row is created, so a failure leaves neither.
     */
    @Transactional
    public ShareItemResponse upload(UUID userId, MultipartFile file, Long requestedTtl) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "no file in the upload");
        }
        String fileName = fileNameOf(file.getOriginalFilename());

        UUID id = UUID.randomUUID();
        BlobStore.Stored stored;
        try (InputStream in = file.getInputStream()) {
            stored = blobStore.store(id, in);
        } catch (IOException e) {
            log.warn("upload of {} failed", fileName, e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "could not read the upload");
        }

        // the client's label is a hint; the bytes decide, and the name is the tie-breaker
        ShareItemType type = ShareItemType.fromMime(file.getContentType(), fileName);
        Duration ttl = resolveTtl(requestedTtl);

        Instant now = Instant.now();
        ShareItem item = new ShareItem(id, userId, type, fileName, stored.storageRef(),
                file.getContentType(), stored.sizeBytes(), now, now.plus(ttl));
        repository.save(item);

        log.info("{} uploaded {} item {} ({} bytes, expires in {}s)", userId, type, id,
                stored.sizeBytes(), ttl.toSeconds());
        String blobUrl = blobLinks.linkFor(id, item.getExpiresAt());
        afterCommit(() -> webSocketHandler.broadcast(userId, ShareItemEvent.created(item, blobUrl)));
        return ShareItemResponse.from(item, blobUrl);
    }

    @Transactional(readOnly = true)
    public List<ShareItemResponse> listLive(UUID userId) {
        return repository.findByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(userId, Instant.now()).stream()
                .map(this::response)
                .toList();
    }

    /**
     * The bytes behind an item.
     *
     * <p>Two ways in, because a browser cannot put a token in an {@code <img src>}: either the
     * caller's token belongs to the owner, or the call carries a signed link that has not expired.
     *
     * <p>A signed link is a bearer credential, but not for somebody who has already said who they
     * are: a paired stranger is turned away even with a valid link, so a forwarded URL cannot be
     * opened from an account that should not see the file. Everything else gets the same 404 as an
     * id that does not exist.
     */
    @Transactional(readOnly = true)
    public Blob blob(UUID id, String linkToken, AuthenticatedUser caller) {
        ShareItem item = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such item: " + id));
        boolean owner = caller != null && caller.userId().equals(item.getUserId());
        if (caller != null && !owner) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such item: " + id);
        }
        if (!owner && !blobLinks.isValid(id, linkToken)) {
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
        ShareItem item = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such item: " + id));
        repository.deleteById(id);
        log.info("{} deleted item {}", userId, id);
        afterCommit(() -> dropBlob(item.getStorageRef()));
        afterCommit(() -> webSocketHandler.broadcast(userId, ShareItemEvent.deleted(id)));
    }

    /**
     * Zero-clutter storage: whatever ran out of time is deleted and its owner's feed is told.
     */
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
            afterCommit(() -> webSocketHandler.broadcast(item.getUserId(),
                    ShareItemEvent.deleted(item.getId())));
        });
    }

    private ShareItemResponse response(ShareItem item) {
        return ShareItemResponse.from(item, blobLinks.linkFor(item.getId(), item.getExpiresAt()));
    }

    private void dropBlob(String storageRef) {
        if (storageRef != null) {
            blobStore.delete(storageRef);
        }
    }

    /** a feed must never hear about a row that did not survive the transaction */
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
        // the name is shown in the feed and echoed in a header, never used as a path
        String cleaned = original.replaceAll("[/\\\\\\r\\n\"]", "_").strip();
        return cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned;
    }
}

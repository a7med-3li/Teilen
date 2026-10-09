package com.backend.item;

import com.backend.auth.domain.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The v1 cloud-relay face of the items API, kept alongside the original routes so nothing that is
 * already deployed has to change. There is no object store on this deployment, so an "upload
 * attempt" is registered against the same multipart endpoint the app already uses; what {@code init}
 * really enforces is the plan's ephemerality invariant — a hard ceiling on how far out an item may
 * expire — plus the size cap.
 *
 * <p>{@code pending} and {@code delete} are the plan's names for what the feed already does.
 */
@RestController
@RequestMapping("/api/v1/items")
public class ItemRelayController {

    private final ShareItemService service;
    private final long relayMaxTtlSeconds;
    private final long maxCiphertextBytes;
    private final long extendTtlSeconds;

    public ItemRelayController(ShareItemService service,
                               @Value("${teilen.relay-max-ttl-seconds:600}") long relayMaxTtlSeconds,
                               @Value("${teilen.max-blob-bytes:31457280}") long maxCiphertextBytes,
                               @Value("${teilen.extend-ttl-seconds:3600}") long extendTtlSeconds) {
        this.service = service;
        this.relayMaxTtlSeconds = relayMaxTtlSeconds;
        this.maxCiphertextBytes = maxCiphertextBytes;
        this.extendTtlSeconds = extendTtlSeconds;
    }

    /**
     * Registers an upload attempt. The client states what it is about to send and until when it
     * wants it to live; both are clamped down to the ephemeral ceiling, and the answer carries the
     * target to send to.
     */
    @PostMapping("/init")
    public RelayInitResponse init(@AuthenticationPrincipal AuthenticatedUser caller,
                                  @Valid @RequestBody RelayInitRequest request) {
        if (request.ciphertextSize() != null && request.ciphertextSize() > maxCiphertextBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "ciphertext is larger than the " + maxCiphertextBytes + " byte cap");
        }
        Instant now = Instant.now();
        Instant ceiling = now.plusSeconds(relayMaxTtlSeconds);
        Instant expiresAt = request.expiresAt() == null || request.expiresAt().isAfter(ceiling)
                ? ceiling
                : request.expiresAt();
        long ttlSeconds = Math.max(1, Duration.between(now, expiresAt).toSeconds());
        return new RelayInitResponse(request.itemId(), "/api/items", expiresAt, maxCiphertextBytes, ttlSeconds);
    }

    /** Everything still live for the caller's account — the plan's pending list. */
    @GetMapping("/pending")
    public List<ShareItemResponse> pending(@AuthenticationPrincipal AuthenticatedUser caller) {
        return service.listLive(caller.userId());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable UUID id) {
        service.delete(caller.userId(), id);
    }

    /** One-tap extension: the plan's 60 minutes, capped by the ordinary TTL ceiling. */
    @PostMapping("/{id}/extend")
    public ShareItemResponse extend(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable UUID id) {
        return service.extend(caller.userId(), id, extendTtlSeconds);
    }

    /** an upload about to happen: a client-chosen id, an optional size, and an optional deadline */
    public record RelayInitRequest(
            UUID itemId,
            Long ciphertextSize,
            Instant expiresAt) {
    }

    /** where to send the bytes, and the constraints the server just agreed to */
    public record RelayInitResponse(
            UUID itemId,
            String uploadUrl,
            Instant expiresAt,
            long maxCiphertextBytes,
            long ttlSeconds) {
    }
}

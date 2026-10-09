package com.backend.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps the browser subscriptions and turns an arrival into the one signal the plan allows: no
 * item id, no type, no size, no content — just "look at your feed". Sending happens off the request
 * thread, on a virtual thread, so a slow push service never slows an upload.
 */
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);

    private final PushSubscriptionRepository repository;
    private final WebPushSender sender;
    private final ExecutorService dispatcher = Executors.newVirtualThreadPerTaskExecutor();

    public PushService(PushSubscriptionRepository repository, WebPushSender sender) {
        this.repository = repository;
        this.sender = sender;
    }

    public String publicKey() {
        return sender.publicKey();
    }

    /** Idempotent: subscribing the same browser again just refreshes its keys. */
    @Transactional
    public void subscribe(UUID userId, PushSubscriptionRequest request) {
        repository.findByUserIdAndEndpoint(userId, request.endpoint())
                .ifPresent(repository::delete);
        repository.save(new PushSubscription(
                UUID.randomUUID(),
                userId,
                request.endpoint(),
                request.keys().p256dh(),
                request.keys().auth(),
                Instant.now()));
        log.info("{} registered a push subscription", userId);
    }

    @Transactional
    public void unsubscribe(UUID userId, String endpoint) {
        repository.deleteByUserIdAndEndpoint(userId, endpoint);
    }

    /**
     * The whole payload the plan permits: a type and a timestamp. Nothing that could identify what
     * arrived, how big it is, or who sent it.
     */
    public void notifyAvailable(UUID userId) {
        if (!sender.enabled()) {
            return;
        }
        List<PushSubscription> subscriptions = repository.findByUserId(userId);
        if (subscriptions.isEmpty()) {
            return;
        }
        byte[] payload = ("{\"type\":\"ITEM_AVAILABLE\",\"timestamp\":" + Instant.now().getEpochSecond() + "}")
                .getBytes(StandardCharsets.UTF_8);
        dispatcher.submit(() -> subscriptions.forEach(subscription -> {
            int status = sender.send(subscription, payload);
            if (status == WebPushSender.GONE || status == WebPushSender.GONE_ALT) {
                // the browser is gone; drop it so we never try again
                repository.deleteByUserIdAndEndpoint(userId, subscription.getEndpoint());
            }
        }));
    }
}

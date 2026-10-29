package com.backend.item;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fan-out of item events, one bucket per account. A browser only ever hears about its own feed:
 * the sessions are filed under the owner the handshake identified, and a broadcast goes to exactly
 * one bucket. Single instance, so no broker is needed.
 */
@Component
public class ShareItemWebSocketHandler extends TextWebSocketHandler {

    /** where the handshake leaves the owner; see FeedHandshakeInterceptor */
    public static final String USER_ID = "teilen.userId";

    private static final Logger log = LoggerFactory.getLogger(ShareItemWebSocketHandler.class);

    private final Map<UUID, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public ShareItemWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        UUID userId = userIdOf(session);
        if (userId == null) {
            // should not happen: the handshake refuses an unauthenticated one
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        sessions.computeIfAbsent(userId, key -> ConcurrentHashMap.newKeySet()).add(session);
        log.info("feed connected for {} ({} open for them)", userId, sessions.get(userId).size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        UUID userId = userIdOf(session);
        forget(session);
        log.info("feed disconnected for {} (status: {})", userId, status);
    }

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            String payload = message.getPayload();
            if (payload == null) {
                return;
            }
            String trimmed = payload.trim().toLowerCase();
            if (trimmed.equals("ping") || trimmed.contains("\"ping\"")) {
                try {
                    synchronized (session) {
                        session.sendMessage(new TextMessage("{\"event\":\"pong\"}"));
                    }
                } catch (Exception ignored) {
                }
                return;
            }
        } catch (Exception ignored) {
            // never throw
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        try {
            log.debug("feed transport error for session {}: {}", session.getId(),
                    exception != null ? exception.getMessage() : "unknown");
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, org.springframework.web.socket.BinaryMessage message) {
        // Safe no-op: ignore binary frames without dropping the connection
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, org.springframework.web.socket.PongMessage message) {
        // Safe no-op for heartbeat pong responses
    }

    public void migrateSessions(UUID fromUserId, UUID toUserId) {
        if (fromUserId == null || toUserId == null || fromUserId.equals(toUserId)) {
            return;
        }
        Set<WebSocketSession> fromSessions = sessions.remove(fromUserId);
        if (fromSessions != null && !fromSessions.isEmpty()) {
            for (WebSocketSession session : fromSessions) {
                if (session.isOpen()) {
                    session.getAttributes().put(USER_ID, toUserId);
                    sessions.computeIfAbsent(toUserId, k -> ConcurrentHashMap.newKeySet()).add(session);
                }
            }
            log.info("migrated {} websocket feed sessions from {} to {}", fromSessions.size(), fromUserId, toUserId);
        }
    }

    public void broadcast(UUID userId, ShareItemEvent event) {
        broadcastToUsers(java.util.List.of(userId), event);
    }

    public void broadcastToUsers(Iterable<UUID> userIds, ShareItemEvent event) {
        if (userIds == null) {
            return;
        }
        String payload = serialize(event);
        for (UUID userId : userIds) {
            Set<WebSocketSession> open = sessions.get(userId);
            if (open == null || open.isEmpty()) {
                continue;
            }
            for (WebSocketSession session : open) {
                if (!session.isOpen()) {
                    forget(session);
                    continue;
                }
                try {
                    synchronized (session) {
                        session.sendMessage(new TextMessage(payload));
                    }
                } catch (IOException e) {
                    log.warn("dropping feed {}: {}", session.getId(), e.getMessage());
                    forget(session);
                }
            }
        }
    }

    public void kickDevice(UUID deviceId) {
        if (deviceId == null) return;
        String payload = serialize(ShareItemEvent.kicked());
        for (Set<WebSocketSession> userSessions : sessions.values()) {
            for (WebSocketSession session : userSessions) {
                Object devAttr = session.getAttributes().get(USER_ID + ".device");
                if (deviceId.equals(devAttr)) {
                    try {
                        synchronized (session) {
                            session.sendMessage(new TextMessage(payload));
                            session.close(CloseStatus.POLICY_VIOLATION);
                        }
                    } catch (IOException e) {
                        log.debug("error kicking device session {}: {}", deviceId, e.getMessage());
                    }
                }
            }
        }
    }

    public void kickOtherDevices(UUID userId, UUID keepDeviceId) {
        if (userId == null) return;
        Set<WebSocketSession> userSessions = sessions.get(userId);
        if (userSessions == null) return;
        String payload = serialize(ShareItemEvent.kicked());
        for (WebSocketSession session : userSessions) {
            Object devAttr = session.getAttributes().get(USER_ID + ".device");
            if (devAttr != null && !devAttr.equals(keepDeviceId)) {
                try {
                    synchronized (session) {
                        session.sendMessage(new TextMessage(payload));
                        session.close(CloseStatus.POLICY_VIOLATION);
                    }
                } catch (IOException e) {
                    log.debug("error kicking other device session: {}", e.getMessage());
                }
            }
        }
    }

    private UUID userIdOf(WebSocketSession session) {
        Object value = session.getAttributes().get(USER_ID);
        return value instanceof UUID userId ? userId : null;
    }

    private void forget(WebSocketSession session) {
        UUID userId = userIdOf(session);
        if (userId == null) {
            return;
        }
        Set<WebSocketSession> open = sessions.get(userId);
        if (open != null && open.remove(session) && open.isEmpty()) {
            sessions.remove(userId, open);
        }
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException e) {
            log.debug("could not close an unidentified feed: {}", e.getMessage());
        }
    }

    public Set<UUID> getConnectedUserIds() {
        return new java.util.HashSet<>(sessions.keySet());
    }

    public int getOpenFeedSocketsCount() {
        return sessions.values().stream().mapToInt(Set::size).sum();
    }

    private String serialize(ShareItemEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (RuntimeException e) {
            throw new IllegalStateException("cannot serialize event", e);
        }
    }
}

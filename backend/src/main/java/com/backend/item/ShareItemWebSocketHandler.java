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
        forget(session);
    }

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) {
        // the feed is read-only; a client sending anything is ignored
    }

    public void broadcast(UUID userId, ShareItemEvent event) {
        Set<WebSocketSession> open = sessions.get(userId);
        if (open == null || open.isEmpty()) {
            return;
        }
        String payload = serialize(event);
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
        log.info("feed disconnected for {}", userId);
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException e) {
            log.debug("could not close an unidentified feed: {}", e.getMessage());
        }
    }

    private String serialize(ShareItemEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (RuntimeException e) {
            throw new IllegalStateException("cannot serialize event", e);
        }
    }
}

package com.backend.signal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The relay in the middle of the WebRTC handshake. It is deliberately dumb: it stamps the sender's
 * device id onto whatever JSON a device sends and hands it to the account's *other* sockets. It
 * never parses an SDP, holds a key, or sees a byte of the transfer — an OFFER, an ANSWER and the
 * ICE candidates are all just opaque frames here.
 *
 * <p>Single instance, like the feed: sessions are bucketed by account, so one browser and one phone
 * sharing an account can find each other and nobody else can.
 */
@Component
public class SignalWebSocketHandler extends TextWebSocketHandler {

    public static final String USER_ID = "teilen.signal.userId";
    public static final String DEVICE_ID = "teilen.signal.deviceId";

    private static final Logger log = LoggerFactory.getLogger(SignalWebSocketHandler.class);

    private final Map<UUID, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public SignalWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        UUID userId = userIdOf(session);
        if (userId == null) {
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        sessions.computeIfAbsent(userId, key -> ConcurrentHashMap.newKeySet()).add(session);
        log.info("signal connected for {} (device {})", userId, deviceIdOf(session));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        forget(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        UUID userId = userIdOf(session);
        String deviceId = deviceIdOf(session);
        if (userId == null || deviceId == null) {
            return;
        }
        String stamped;
        try {
            // the client's own "from" is overwritten: the socket already proved which device this is
            ObjectNode node = (ObjectNode) objectMapper.readTree(message.getPayload());
            node.put("from", deviceId);
            stamped = objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            log.debug("ignoring a malformed signal on {}: {}", session.getId(), e.getMessage());
            return;
        }
        relay(userId, session, stamped);
    }

    private void relay(UUID userId, WebSocketSession from, String payload) {
        Set<WebSocketSession> open = sessions.get(userId);
        if (open == null) {
            return;
        }
        for (WebSocketSession session : open) {
            if (session == from || !session.isOpen()) {
                continue;
            }
            try {
                synchronized (session) {
                    session.sendMessage(new TextMessage(payload));
                }
            } catch (IOException e) {
                log.warn("dropping signal socket {}: {}", session.getId(), e.getMessage());
                forget(session);
            }
        }
    }

    private UUID userIdOf(WebSocketSession session) {
        Object value = session.getAttributes().get(USER_ID);
        return value instanceof UUID userId ? userId : null;
    }

    private String deviceIdOf(WebSocketSession session) {
        Object value = session.getAttributes().get(DEVICE_ID);
        return value == null ? null : value.toString();
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
            log.debug("could not close an unidentified signal socket: {}", e.getMessage());
        }
    }
}

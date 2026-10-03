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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fan-out of item events to every open feed. Single instance, single user, no broker needed.
 */
@Component
public class ShareItemWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ShareItemWebSocketHandler.class);

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final ObjectMapper objectMapper;

    public ShareItemWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("feed connected: {} ({} open)", session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("feed disconnected: {} ({} open)", session.getId(), sessions.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // the feed is read-only; a client sending anything is ignored
    }

    public void broadcast(ShareItemEvent event) {
        if (sessions.isEmpty()) {
            return;
        }
        String payload = serialize(event);
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                sessions.remove(session);
                continue;
            }
            try {
                synchronized (session) {
                    session.sendMessage(new TextMessage(payload));
                }
            } catch (IOException e) {
                log.warn("dropping feed {}: {}", session.getId(), e.getMessage());
                sessions.remove(session);
            }
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

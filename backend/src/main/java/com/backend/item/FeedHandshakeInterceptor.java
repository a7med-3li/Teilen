package com.backend.item;

import com.backend.auth.domain.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Puts the account on the websocket session.
 *
 * <p>No token, no handshake: the security chain has already refused it by the time this runs, and
 * anything that gets this far without an identity is refused again here, so an unauthenticated
 * socket can never join a feed.
 */
public class FeedHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(FeedHandshakeInterceptor.class);

    @Override
    public boolean beforeHandshake(@NonNull ServerHttpRequest request,
                                   @NonNull ServerHttpResponse response,
                                   @NonNull WebSocketHandler handler,
                                   @NonNull Map<String, Object> attributes) {
        AuthenticatedUser user = callerOf(request);
        if (user == null) {
            log.debug("refusing a websocket handshake without a token: {}", request.getURI());
            return false;
        }
        attributes.put(ShareItemWebSocketHandler.USER_ID, user.userId());
        attributes.put(ShareItemWebSocketHandler.USER_ID + ".device", user.deviceId());
        return true;
    }

    @Override
    public void afterHandshake(@NonNull ServerHttpRequest request,
                               @NonNull ServerHttpResponse response,
                               @NonNull WebSocketHandler handler,
                               Exception exception) {
        // nothing to clean up: the identity lives on the session, not on the handshake
    }

    /**
     * The filter chain has already run on this thread, so the context is the cheap place to look;
     * the request's own principal is the fallback in case the context was cleared on the way.
     */
    private AuthenticatedUser callerOf(ServerHttpRequest request) {
        Authentication fromContext = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser user = principalOf(fromContext);
        if (user != null) {
            return user;
        }
        Object principal = request.getPrincipal();
        return principal instanceof Authentication authentication ? principalOf(authentication) : null;
    }

    private AuthenticatedUser principalOf(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user
                ? user
                : null;
    }
}

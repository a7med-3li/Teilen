package com.backend.signal;

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
 * Puts the account and the device on a signaling socket. Same rule as the feed: no token, no
 * handshake. The device id is what lets the relay stamp a sender the receiver can trust.
 */
public class SignalHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SignalHandshakeInterceptor.class);

    @Override
    public boolean beforeHandshake(@NonNull ServerHttpRequest request,
                                   @NonNull ServerHttpResponse response,
                                   @NonNull WebSocketHandler handler,
                                   @NonNull Map<String, Object> attributes) {
        AuthenticatedUser user = callerOf(request);
        if (user == null) {
            log.debug("refusing a signaling handshake without a token: {}", request.getURI());
            return false;
        }
        attributes.put(SignalWebSocketHandler.USER_ID, user.userId());
        attributes.put(SignalWebSocketHandler.DEVICE_ID, user.deviceId());
        return true;
    }

    @Override
    public void afterHandshake(@NonNull ServerHttpRequest request,
                               @NonNull ServerHttpResponse response,
                               @NonNull WebSocketHandler handler,
                               Exception exception) {
        // the identity lives on the session
    }

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

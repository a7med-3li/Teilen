package com.backend.auth.security;

import com.backend.auth.domain.AuthenticatedUser;
import com.backend.auth.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Turns a bearer token or HttpOnly session cookie into an authenticated caller.
 * Verifies optional Device Proof-of-Possession (DPoP / X-Teilen-Signature) against
 * the device's non-exportable hardware/WebCrypto identity key.
 */
public class BearerTokenFilter extends OncePerRequestFilter {

    public static final String SESSION_COOKIE_NAME = "teilen_session";
    public static final String SIGNATURE_HEADER = "X-Teilen-Signature";
    public static final String TIMESTAMP_HEADER = "X-Teilen-Timestamp";
    public static final String PUBLIC_KEY_HEADER = "X-Teilen-Public-Key";

    private final AuthService auth;
    private final Ed25519Verifier verifier;

    public BearerTokenFilter(AuthService auth, Ed25519Verifier verifier) {
        this.auth = auth;
        this.verifier = verifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            AuthenticatedUser user = auth.authenticate(token);
            if (user != null) {
                // Verify optional DPoP device signature if presented or if device requires it
                String signature = request.getHeader(SIGNATURE_HEADER);
                if (signature != null && !signature.isBlank()) {
                    String timestampStr = request.getHeader(TIMESTAMP_HEADER);
                    long ts = 0L;
                    try {
                        ts = Long.parseLong(timestampStr);
                    } catch (Exception ignored) {
                    }

                    long now = System.currentTimeMillis();
                    if (Math.abs(now - ts) > 300_000L) { // 5 minutes skew tolerance
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Request signature timestamp expired or skewed");
                        return;
                    }

                    String payload = request.getMethod().toUpperCase() + "|" + request.getRequestURI() + "|" + ts;
                    byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);

                    String storedKey = user.devicePublicKey();
                    boolean valid = false;

                    // 1. Try verifying against the stored device public key
                    if (storedKey != null && !storedKey.isBlank()) {
                        valid = verifier.verify(storedKey, payloadBytes, signature);
                    }

                    // 2. Self-healing / Trust-On-First-Use: if stored key was missing or mismatched (e.g. legacy X25519 key),
                    // allow verification with presented public key and update the device's public key in database.
                    String presentedKey = request.getHeader(PUBLIC_KEY_HEADER);
                    if (!valid && presentedKey != null && !presentedKey.isBlank()) {
                        if (verifier.verify(presentedKey, payloadBytes, signature)) {
                            valid = true;
                            auth.updateDevicePublicKey(user.deviceId(), presentedKey);
                        }
                    }

                    if (!valid && storedKey != null && !storedKey.isBlank()) {
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Device signature verification failed");
                        return;
                    }
                }

                var authentication = new UsernamePasswordAuthenticationToken(
                        user, token, AuthorityUtils.NO_AUTHORITIES);
                SecurityContextHolder.getContext().setAuthentication(authentication);
                auth.touch(user.deviceId());
            }
        }
        chain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        // 1. Authorization: Bearer <token>
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String value = header.substring(7).strip();
            if (!value.isEmpty()) {
                return value;
            }
        }

        // 2. HttpOnly Cookie: `teilen_session`
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (SESSION_COOKIE_NAME.equals(c.getName()) || "__Host-teilen_session".equals(c.getName())) {
                    String val = c.getValue();
                    if (val != null && !val.isBlank()) {
                        return val.strip();
                    }
                }
            }
        }

        // 3. WebSocket query parameter: ?token=
        if (isWebSocketHandshake(request)) {
            String value = request.getParameter("token");
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }

    private boolean isWebSocketHandshake(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && (uri.endsWith("/ws") || uri.endsWith("/ws/signal"));
    }
}

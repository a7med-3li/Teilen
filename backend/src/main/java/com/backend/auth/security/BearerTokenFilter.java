package com.backend.auth.security;

import com.backend.auth.domain.AuthenticatedUser;
import com.backend.auth.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Turns a bearer token into an authenticated caller, or leaves the request anonymous and lets the
 * rules decide.
 *
 * <p>The websocket handshake is the one place a browser cannot set a header, so for that path the
 * token may also arrive as {@code /ws?token=…}. Nothing else reads it from the query string.
 *
 * <p>There is no cache and no session: the token is the whole of the identity, on every request.
 */
public class BearerTokenFilter extends OncePerRequestFilter {

    private final AuthService auth;

    public BearerTokenFilter(AuthService auth) {
        this.auth = auth;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = bearerToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            AuthenticatedUser user = auth.authenticate(token);
            if (user != null) {
                var authentication = new UsernamePasswordAuthenticationToken(
                        user, token, AuthorityUtils.NO_AUTHORITIES);
                SecurityContextHolder.getContext().setAuthentication(authentication);
                // coarse on purpose: one write per device every five minutes, not one per request
                auth.touch(user.deviceId());
            }
        }
        chain.doFilter(request, response);
    }

    private String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String value = header.substring(7).strip();
            if (!value.isEmpty()) {
                return value;
            }
        }
        if (isWebSocketHandshake(request)) {
            String value = request.getParameter("token");
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }

    /** Spring maps the handshake to this handler, so the path is a reliable marker */
    private boolean isWebSocketHandshake(HttpServletRequest request) {
        return request.getRequestURI() != null && request.getRequestURI().endsWith("/ws");
    }
}

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

public class BearerTokenFilter extends OncePerRequestFilter {

    public static final String SESSION_COOKIE_NAME = "teilen_session";

    private final AuthService auth;

    public BearerTokenFilter(AuthService auth) {
        this.auth = auth;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (uri != null && uri.startsWith("/api/admin/")) {
            chain.doFilter(request, response);
            return;
        }

        String token = resolveToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            AuthenticatedUser user = auth.authenticate(token);
            if (user != null) {
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

        // 2. Cookie fallback
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
        return uri != null && (uri.contains("/ws"));
    }
}
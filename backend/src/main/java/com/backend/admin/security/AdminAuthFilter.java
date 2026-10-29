package com.backend.admin.security;

import com.backend.admin.service.AdminAuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class AdminAuthFilter extends OncePerRequestFilter {

    private final AdminAuthService adminAuthService;

    public AdminAuthFilter(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();

        // Only process /api/admin/** requests
        if (uri == null || !uri.startsWith("/api/admin/")) {
            filterChain.doFilter(request, response);
            return;
        }

        // /api/admin/login is open
        if ("/api/admin/login".equals(uri)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = resolveAdminToken(request);
        if (token == null || token.isBlank()) {
            sendUnauthorized(response, "Admin authentication required");
            return;
        }

        AuthenticatedAdmin admin = adminAuthService.authenticate(token);
        if (admin == null) {
            sendUnauthorized(response, "Admin session invalid or expired");
            return;
        }

        var auth = new UsernamePasswordAuthenticationToken(
                admin,
                token,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);

        filterChain.doFilter(request, response);
    }

    private String resolveAdminToken(HttpServletRequest request) {
        // 1. X-Admin-Token header
        String customHeader = request.getHeader("X-Admin-Token");
        if (customHeader != null && !customHeader.isBlank()) {
            return customHeader.strip();
        }

        // 2. Authorization: Bearer <token>
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String value = authHeader.substring(7).strip();
            if (!value.isEmpty()) {
                return value;
            }
        }

        // 3. Cookie fallback
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if ("teilen_admin_token".equals(c.getName())) {
                    String val = c.getValue();
                    if (val != null && !val.isBlank()) {
                        return val.strip();
                    }
                }
            }
        }

        return null;
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"" + message + "\"}");
    }
}

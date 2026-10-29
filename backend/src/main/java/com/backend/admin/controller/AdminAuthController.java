package com.backend.admin.controller;

import com.backend.admin.dto.AdminLoginRequest;
import com.backend.admin.dto.AdminLoginResponse;
import com.backend.admin.service.AdminAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminAuthController {

    private final AdminAuthService adminAuthService;

    public AdminAuthController(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    @PostMapping("/login")
    public ResponseEntity<AdminLoginResponse> login(@Valid @RequestBody AdminLoginRequest request,
                                                    HttpServletRequest httpRequest,
                                                    HttpServletResponse httpResponse) {
        String clientIp = clientIp(httpRequest);
        AdminLoginResponse response = adminAuthService.login(request, clientIp);
        setAdminCookie(httpResponse, httpRequest, response.adminToken());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(
            @RequestHeader(value = "X-Admin-Token", required = false) String adminTokenHeader,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        String token = adminTokenHeader;
        if (token == null && authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = authHeader.substring(7).strip();
        }
        if (token != null) {
            adminAuthService.logout(token);
        }
        clearAdminCookie(httpResponse, httpRequest);
        return ResponseEntity.ok(Map.of("status", "logged_out"));
    }

    private void setAdminCookie(HttpServletResponse response, HttpServletRequest request, String token) {
        boolean isSecure = request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
        ResponseCookie cookie = ResponseCookie.from("teilen_admin_token", token)
                .httpOnly(true)
                .secure(isSecure)
                .path("/")
                .maxAge(Duration.ofHours(12))
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearAdminCookie(HttpServletResponse response, HttpServletRequest request) {
        boolean isSecure = request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
        ResponseCookie cookie = ResponseCookie.from("teilen_admin_token", "")
                .httpOnly(true)
                .secure(isSecure)
                .path("/")
                .maxAge(0)
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String realIp = req.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return req.getRemoteAddr();
    }
}


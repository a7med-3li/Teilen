package com.backend.auth.controller;

import com.backend.auth.QrCodes;
import com.backend.auth.domain.AuthenticatedUser;
import com.backend.auth.domain.PairingStartResponse;
import com.backend.auth.domain.StartPairingRequest;
import com.backend.auth.dto.ApprovalResponse;
import com.backend.auth.dto.ClaimTokenRequest;
import com.backend.auth.dto.CreateAccountRequest;
import com.backend.auth.dto.DeviceResponse;
import com.backend.auth.dto.LoginRequest;
import com.backend.auth.dto.PairingDecisionRequest;
import com.backend.auth.dto.PairingPreview;
import com.backend.auth.dto.SessionResponse;
import com.backend.auth.dto.TokenClaimResponse;
import com.backend.auth.dto.UserResponse;
import com.backend.auth.security.BearerTokenFilter;
import com.backend.auth.service.AuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
public class AuthController {

    private final AuthService auth;
    private final QrCodes qrCodes;

    public AuthController(AuthService auth, QrCodes qrCodes) {
        this.auth = auth;
        this.qrCodes = qrCodes;
    }

    /** creates an account with username and password, pairs this device, sets HttpOnly cookie */
    @PostMapping("/api/auth/account")
    public ResponseEntity<SessionResponse> createAccount(@Valid @RequestBody CreateAccountRequest request,
                                                         HttpServletRequest httpRequest,
                                                         HttpServletResponse httpResponse) {
        String ip = clientIp(httpRequest);
        String platform = httpRequest.getHeader("User-Agent");
        SessionResponse session = auth.createAccount(
                request.username(), request.password(), request.displayName(),
                request.deviceName(), request.deviceType(),
                request.devicePublicKey(), ip, platform
        );
        setSessionCookie(httpResponse, httpRequest, session.token());
        return ResponseEntity.status(HttpStatus.CREATED).body(session);
    }

    /** logs in with username and password, pairs this device, sets HttpOnly cookie */
    @PostMapping("/api/auth/login")
    public ResponseEntity<SessionResponse> login(@Valid @RequestBody LoginRequest request,
                                                 HttpServletRequest httpRequest,
                                                 HttpServletResponse httpResponse) {
        String ip = clientIp(httpRequest);
        String platform = httpRequest.getHeader("User-Agent");
        SessionResponse session = auth.login(
                request.username(), request.password(),
                request.deviceName(), request.deviceType(),
                request.devicePublicKey(), ip, platform
        );
        setSessionCookie(httpResponse, httpRequest, session.token());
        return ResponseEntity.ok(session);
    }

    /** Silently refreshes token using HttpOnly session cookie, rotating refresh token */
    @PostMapping("/api/auth/refresh")
    public ResponseEntity<SessionResponse> refresh(HttpServletRequest httpRequest,
                                                   HttpServletResponse httpResponse) {
        String currentToken = extractCookieToken(httpRequest);
        if (currentToken == null || currentToken.isBlank()) {
            String header = httpRequest.getHeader("Authorization");
            if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                currentToken = header.substring(7).strip();
            }
        }
        if (currentToken == null || currentToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing_session_cookie");
        }

        String ip = clientIp(httpRequest);
        SessionResponse session = auth.refreshToken(currentToken, ip);
        setSessionCookie(httpResponse, httpRequest, session.token());
        return ResponseEntity.ok(session);
    }

    @PostMapping("/api/auth/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal AuthenticatedUser caller,
                                       HttpServletRequest httpRequest,
                                       HttpServletResponse httpResponse) {
        clearSessionCookie(httpResponse, httpRequest);
        if (caller != null) {
            try {
                auth.revokeDevice(caller.userId(), caller.deviceId(), caller.deviceId());
            } catch (Exception ignored) {
            }
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/me")
    public UserResponse me(@AuthenticationPrincipal AuthenticatedUser caller) {
        return auth.me(caller.userId());
    }

    // ---------------------------------------------------------------- pairing

    @PostMapping("/api/auth/device/code")
    @ResponseStatus(HttpStatus.CREATED)
    public PairingStartResponse startPairing(@Valid @RequestBody StartPairingRequest request) {
        return auth.startPairing(request.deviceName(), request.deviceType(), request.platform(),
                request.publicKey());
    }

    @GetMapping(value = "/api/auth/device/qr.svg", produces = "image/svg+xml")
    public ResponseEntity<String> qr(@RequestParam String code) {
        String uri = (code.startsWith("teilen://") || code.startsWith("http://") || code.startsWith("https://"))
                ? code
                : auth.pairingUri(code);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(qrCodes.svg(uri));
    }

    @GetMapping("/api/auth/device/pending")
    public PairingPreview pending(@RequestParam String code) {
        return PairingPreview.of(auth.pendingByCode(code));
    }

    @PostMapping("/api/auth/device/approve")
    public ApprovalResponse approve(@AuthenticationPrincipal AuthenticatedUser caller,
                                    @Valid @RequestBody PairingDecisionRequest request) {
        return auth.approve(caller.userId(), request.userCode(), request.keyPackage());
    }

    @PostMapping("/api/auth/device/deny")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deny(@AuthenticationPrincipal AuthenticatedUser caller,
                     @Valid @RequestBody PairingDecisionRequest request) {
        auth.deny(caller.userId(), request.userCode());
    }

    @PostMapping("/api/auth/device/token")
    public ResponseEntity<?> claimToken(@Valid @RequestBody ClaimTokenRequest request,
                                        HttpServletRequest httpRequest,
                                        HttpServletResponse httpResponse) {
        TokenClaimResponse claim = auth.claim(request.deviceCode());
        if (claim == null) {
            return ResponseEntity.accepted().body(new PendingResponse(Instant.now().toString()));
        }
        setSessionCookie(httpResponse, httpRequest, claim.token());
        return ResponseEntity.ok(claim);
    }

    private record PendingResponse(String status) {
    }

    // ---------------------------------------------------------------- devices

    @GetMapping("/api/devices")
    public List<DeviceResponse> devices(@AuthenticationPrincipal AuthenticatedUser caller) {
        return auth.devices(caller.userId(), caller.deviceId());
    }

    @DeleteMapping("/api/devices/others")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeOthers(@AuthenticationPrincipal AuthenticatedUser caller) {
        auth.revokeOtherDevices(caller.userId(), caller.deviceId());
    }

    @DeleteMapping("/api/devices/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable UUID id) {
        auth.revokeDevice(caller.userId(), id, caller.deviceId());
    }

    // ---------------------------------------------------------------- helpers

    private void setSessionCookie(HttpServletResponse response, HttpServletRequest request, String token) {
        boolean isSecure = request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
        ResponseCookie cookie = ResponseCookie.from(BearerTokenFilter.SESSION_COOKIE_NAME, token)
                .httpOnly(true)
                .secure(isSecure)
                .path("/")
                .maxAge(Duration.ofDays(90))
                .sameSite("Strict")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearSessionCookie(HttpServletResponse response, HttpServletRequest request) {
        boolean isSecure = request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
        ResponseCookie cookie = ResponseCookie.from(BearerTokenFilter.SESSION_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(isSecure)
                .path("/")
                .maxAge(0)
                .sameSite("Strict")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String extractCookieToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (BearerTokenFilter.SESSION_COOKIE_NAME.equals(c.getName()) || "__Host-teilen_session".equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].strip();
        }
        return request.getRemoteAddr();
    }
}

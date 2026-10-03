package com.backend.auth;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The whole auth surface, in the shape of RFC 8628 so it reads like something you already know:
 *
 * <pre>
 * POST /api/auth/account         the first phone creates the account, is paired at once
 * POST /api/auth/device/code     a newcomer asks for a code                     (open)
 * POST /api/auth/device/approve  a paired device approves it
 * POST /api/auth/device/deny     …or does not
 * POST /api/auth/device/token    the newcomer collects its token                (open)
 * GET  /api/auth/device/qr.svg   the code as a QR                               (open)
 * GET  /api/auth/device/pending  what that code is asking for                   (open)
 * GET  /api/me                   who am I
 * GET  /api/devices              everything paired to this account
 * DELETE /api/devices/{id}       unpair one
 * </pre>
 */
@RestController
public class AuthController {

    private final AuthService auth;
    private final QrCodes qrCodes;

    public AuthController(AuthService auth, QrCodes qrCodes) {
        this.auth = auth;
        this.qrCodes = qrCodes;
    }

    /** the very first phone: a phone number, and the account plus a token come back */
    @PostMapping("/api/auth/account")
    public ResponseEntity<SessionResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        SessionResponse session = auth.createAccount(request.phone(), request.displayName(), request.deviceName());
        return ResponseEntity.status(HttpStatus.CREATED).body(session);
    }

    @GetMapping("/api/me")
    public UserResponse me(@AuthenticationPrincipal AuthenticatedUser caller) {
        return auth.me(caller.userId());
    }

    // ---------------------------------------------------------------- pairing

    @PostMapping("/api/auth/device/code")
    @ResponseStatus(HttpStatus.CREATED)
    public PairingStartResponse startPairing(@Valid @RequestBody StartPairingRequest request) {
        return auth.startPairing(request.deviceName(), request.deviceType(), request.platform());
    }

    /** the QR encodes a deep link, so the phone's own camera is all the scanning hardware needed */
    @GetMapping(value = "/api/auth/device/qr.svg", produces = "image/svg+xml")
    public ResponseEntity<String> qr(@RequestParam String code) {
        // 404 for a code that was never issued or is already spent: the QR is drawn only for a
        // live request, so a stale pairing screen cannot keep offering itself
        auth.pendingByCode(code);
        return ResponseEntity.ok()
                // the code changes every time, and it is a credential for a couple of minutes
                .header("Cache-Control", "no-store")
                .body(qrCodes.svg(auth.pairingUri(code)));
    }

    /**
     * What a code is asking for, so the phone can name the newcomer before anyone taps allow.
     *
     * <p>Open, like the QR beside it: the code is the credential for a couple of minutes, so
     * holding it already means being that device. It answers with a name and a kind — nothing
     * about the account it would join.
     */
    @GetMapping("/api/auth/device/pending")
    public PairingPreview pending(@RequestParam String code) {
        return PairingPreview.of(auth.pendingByCode(code));
    }

    @PostMapping("/api/auth/device/approve")
    public ApprovalResponse approve(@AuthenticationPrincipal AuthenticatedUser caller,
                                    @Valid @RequestBody PairingDecisionRequest request) {
        return auth.approve(caller.userId(), request.userCode());
    }

    @PostMapping("/api/auth/device/deny")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deny(@AuthenticationPrincipal AuthenticatedUser caller,
                     @Valid @RequestBody PairingDecisionRequest request) {
        auth.deny(caller.userId(), request.userCode());
    }

    /** 202 while nobody has answered yet, so the caller knows to keep waiting rather than to give up */
    @PostMapping("/api/auth/device/token")
    public ResponseEntity<?> claimToken(@Valid @RequestBody ClaimTokenRequest request) {
        TokenClaimResponse claim = auth.claim(request.deviceCode());
        if (claim == null) {
            return ResponseEntity.accepted().body(new PendingResponse(Instant.now().toString()));
        }
        return ResponseEntity.ok(claim);
    }

    private record PendingResponse(String status) {
    }

    // ---------------------------------------------------------------- devices

    @GetMapping("/api/devices")
    public List<DeviceResponse> devices(@AuthenticationPrincipal AuthenticatedUser caller) {
        return auth.devices(caller.userId(), caller.deviceId());
    }

    @DeleteMapping("/api/devices/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable UUID id) {
        auth.revokeDevice(caller.userId(), id, caller.deviceId());
    }
}

package com.backend.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Accounts, devices, and the pairing dance between them.
 *
 * <p>The shape of it: the first phone creates an account with a phone number and is paired on the
 * spot. Every other device — browsers, a spare phone — asks for a code and waits for a device that
 * is already paired to approve it, then collects its own token by polling. Nothing else is
 * trusted, and no code is ever guessable from what it says.
 *
 * <p>Known gap, deliberately: creating an account proves nothing about the number. Whoever asks
 * for a number first owns it. An OTP is the one thing missing, and it would slot into
 * {@link #createAccount} without changing anything else here.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** how long a pairing request stays answerable */
    private final Duration pairingTtl;
    /** what a waiting device is told to wait between polls */
    private final Duration pollInterval;

    private final UserRepository users;
    private final DeviceRepository devices;
    private final PairingRequestRepository pairings;

    public AuthService(UserRepository users,
                       DeviceRepository devices,
                       PairingRequestRepository pairings,
                       @Value("${teilen.auth.pairing-ttl-seconds:300}") long pairingTtlSeconds,
                       @Value("${teilen.auth.poll-interval-seconds:3}") long pollIntervalSeconds) {
        this.users = users;
        this.devices = devices;
        this.pairings = pairings;
        this.pairingTtl = Duration.ofSeconds(pairingTtlSeconds);
        this.pollInterval = Duration.ofSeconds(pollIntervalSeconds);
    }

    // ------------------------------------------------------------------ accounts

    /**
     * Creates the account and pairs the phone that asked for it.
     *
     * @throws ResponseStatusException 409 if the number is already taken — the caller should pair
     *                                  that device from an existing one instead
     */
    @Transactional
    public SessionResponse createAccount(String rawPhone, String displayName, String rawDeviceName) {
        String phone = requirePhone(rawPhone);
        if (users.findByPhone(phone).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "account_exists: this number already has an account, pair this device instead");
        }

        Instant now = Instant.now();
        User user = new User(UUID.randomUUID(), phone, clean(displayName, 80), now);
        users.save(user);
        SessionResponse session = pairDevice(user, DeviceType.PHONE, rawDeviceName, now);
        log.info("created account {} and paired the first device {}", phone, session.deviceName());
        return session;
    }

    @Transactional(readOnly = true)
    public UserResponse me(UUID userId) {
        return UserResponse.from(findUser(userId));
    }

    // ------------------------------------------------------------------ pairing

    /**
     * Step one, called by a device that has nothing yet: hand back a code to show and a secret to
     * poll with. Unauthenticated on purpose — this is the beginning of the trust chain, not a step
     * in it.
     */
    @Transactional
    public PairingStartResponse startPairing(String rawDeviceName, String requestedType, String platform) {
        DeviceType type = DeviceType.parse(requestedType);
        Instant now = Instant.now();

        // the polling secret is generated here, hashed on the way in, and returned exactly once
        String deviceCode = Tokens.newToken();
        PairingRequest request = null;
        String userCode = null;
        // the code is short, so it can collide; the unique index is what actually guarantees it
        for (int attempt = 0; attempt < 6; attempt++) {
            userCode = Tokens.newUserCode();
            try {
                request = new PairingRequest(UUID.randomUUID(),
                        Tokens.hash(deviceCode),
                        Tokens.hash(userCode),
                        clean(rawDeviceName, 120),
                        type,
                        clean(platform, 160),
                        now,
                        now.plus(pairingTtl));
                pairings.save(request);
                break;
            } catch (DataIntegrityViolationException e) {
                log.debug("pairing code {} already taken, retrying", userCode);
            }
        }
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "could not find a free pairing code, try again");
        }

        String formatted = Tokens.formatUserCode(userCode);
        return new PairingStartResponse(deviceCode, formatted,
                pairingTtl.toSeconds(),
                pollInterval.toSeconds(),
                "/api/auth/device/qr.svg?code=" + formatted,
                pairingUri(formatted),
                request.getExpiresAt());
    }

    /**
     * Step two, from a device that is already paired: the code from the QR or typed in. Mints the
     * newcomer's token now; the newcomer picks it up by polling.
     */
    @Transactional
    public ApprovalResponse approve(UUID userId, String rawUserCode) {
        Instant now = Instant.now();
        PairingRequest request = findPairingByUserCode(rawUserCode);

        if (request.isExpired(now)) {
            throw new ResponseStatusException(HttpStatus.GONE, "pairing_expired");
        }
        if (!request.isPending()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "pairing_" + request.getStatus().name().toLowerCase() + ": this code was already used");
        }

        User owner = findUser(userId);
        String token = Tokens.newToken();
        Device device = new Device(UUID.randomUUID(), owner.getId(), request.getDeviceType(),
                request.getDeviceName(), Tokens.hash(token), now);
        devices.save(device);
        request.approve(device.getId(), token, now);

        log.info("{} approved a {} device: {}", request.describe(), request.getDeviceType(),
                request.getDeviceName());
        return new ApprovalResponse(request.getDeviceName(), request.getDeviceType(),
                request.describe(), request.getExpiresAt());
    }

    /** step two, the other answer */
    @Transactional
    public void deny(UUID userId, String rawUserCode) {
        PairingRequest request = findPairingByUserCode(rawUserCode);
        if (!request.isPending()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "pairing_already_resolved");
        }
        request.deny(Instant.now());
        log.info("{} denied a pairing request from {}", findUser(userId).label(), request.getDeviceName());
    }

    /**
     * Step three, back on the newcomer: give me my token.
     *
     * @return the token, or null while nobody has approved yet (the caller answers 202)
     */
    @Transactional
    public TokenClaimResponse claim(String deviceCode) {
        Instant now = Instant.now();
        PairingRequest request = pairings.findByDeviceCodeHash(Tokens.hash(nullToEmpty(deviceCode)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown_device_code"));

        if (request.isExpired(now)) {
            throw new ResponseStatusException(HttpStatus.GONE, "pairing_expired");
        }
        if (request.isPending() || request.getTokenValue() == null) {
            switch (request.getStatus()) {
                case PENDING -> {
                    return null; // still waiting: not an error
                }
                case DENIED -> throw new ResponseStatusException(HttpStatus.FORBIDDEN, "pairing_denied");
                default -> throw new ResponseStatusException(HttpStatus.GONE,
                        "pairing_" + request.getStatus().name().toLowerCase());
            }
        }

        // exactly once: the row keeps a hash, this is the only time the token is readable
        String token = request.deliver(now);
        Device device = devices.findById(request.getDeviceId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE, "pairing_gone"));
        User user = findUser(device.getUserId());
        log.info("paired {} device {} for {}", request.getDeviceType(), device.getName(), user.label());
        return new TokenClaimResponse(token, device.getId(), device.getDeviceType(), device.getName(),
                UserResponse.from(user));
    }

    /** the code behind a QR, so the phone knows which request the user is approving */
    @Transactional(readOnly = true)
    public PairingRequest pendingByCode(String rawUserCode) {
        PairingRequest request = findPairingByUserCode(rawUserCode);
        if (!request.isPending() || request.isExpired(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "pairing_expired");
        }
        return request;
    }

    /** pairing requests are noise once their moment has passed, answered or not */
    @Scheduled(fixedDelayString = "${teilen.auth.sweep-interval-ms:60000}")
    @Transactional
    public void sweepPairings() {
        long gone = pairings.deleteByExpiresAtBefore(Instant.now().minus(pairingTtl));
        if (gone > 0) {
            log.debug("swept {} stale pairing request(s)", gone);
        }
    }

    // ------------------------------------------------------------------ devices

    @Transactional(readOnly = true)
    public List<DeviceResponse> devices(UUID userId, UUID currentDeviceId) {
        return devices.findByUserIdOrderByCreatedAtAsc(userId).stream()
                .map(device -> DeviceResponse.from(device, device.getId().equals(currentDeviceId)))
                .toList();
    }

    /**
     * Pulls a device's token.
     *
     * <p>A device may drop itself, which is how signing out actually unpairs: the account just has
     * to have another way in, or revoking the last one would leave nobody able to approve the next
     * pairing. That is the whole rule — sign out, and everything else follows.
     */
    @Transactional
    public void revokeDevice(UUID userId, UUID deviceId, UUID revokingDeviceId) {
        Device device = devices.findById(deviceId)
                .filter(d -> d.getUserId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such device"));
        if (deviceId.equals(revokingDeviceId)
                && devices.countByUserIdAndIdNot(userId, deviceId) == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "cannot_revoke_self: this is the only paired device, so revoking it would lock the "
                            + "account out — pair another one first, or just leave it signed in");
        }
        devices.delete(device);
        log.info("{} revoked device {}", findUser(userId).label(), device.getName());
    }

    /** the token is valid, so note that this device is alive — at most one write every 5 minutes */
    @Transactional
    public void touch(UUID deviceId) {
        Instant now = Instant.now();
        devices.touch(deviceId, now, now.minus(Duration.ofMinutes(5)));
    }

    // ------------------------------------------------------------------ helpers

    /** resolves a bearer token to the device it belongs to; null when it is not one of ours */
    @Transactional(readOnly = true)
    public AuthenticatedUser authenticate(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Device device = devices.findByTokenHash(Tokens.hash(token)).orElse(null);
        if (device == null) {
            return null;
        }
        User user = users.findById(device.getUserId()).orElse(null);
        if (user == null) {
            return null;
        }
        return new AuthenticatedUser(user.getId(), device.getId(), user.getPhone());
    }

    private SessionResponse pairDevice(User user, DeviceType type, String rawDeviceName, Instant now) {
        String name = clean(rawDeviceName, 120);
        if (name.isBlank()) {
            name = type == DeviceType.PHONE ? "phone" : "browser";
        }
        String token = Tokens.newToken();
        Device device = new Device(UUID.randomUUID(), user.getId(), type, name, Tokens.hash(token), now);
        devices.save(device);
        return new SessionResponse(token, device.getId(), device.getName(), UserResponse.from(user));
    }

    private PairingRequest findPairingByUserCode(String rawUserCode) {
        String compact = Tokens.normalizeUserCode(rawUserCode);
        if (compact.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "no pairing code given");
        }
        return pairings.findByUserCodeHash(Tokens.hash(compact))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown_pairing_code"));
    }

    private User findUser(UUID userId) {
        return users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "no such account"));
    }

    private String requirePhone(String raw) {
        String phone = Tokens.normalizePhone(raw);
        int digits = phone.startsWith("+") ? phone.length() - 1 : phone.length();
        if (digits < 6 || digits > 20) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "that does not look like a phone number — 6 to 20 digits, an optional leading +");
        }
        return phone;
    }

    /** one line, no control characters, no longer than asked: these are shown in a device list */
    private static String clean(String raw, int max) {
        if (raw == null) {
            return null;
        }
        String flat = raw.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").strip();
        return flat.length() > max ? flat.substring(0, max) : flat;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** {@code teilen://pair?code=K7PM-3XQD}: the phone's camera opens this and the app claims it */
    public static String pairingUri(String rawUserCode) {
        return "teilen://pair?code=" + Tokens.formatUserCode(rawUserCode);
    }
}

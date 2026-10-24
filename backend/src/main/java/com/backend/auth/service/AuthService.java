package com.backend.auth.service;

import com.backend.auth.domain.AuthenticatedUser;
import com.backend.auth.domain.PairingStartResponse;
import com.backend.auth.domain.Tokens;
import com.backend.auth.dto.ApprovalResponse;
import com.backend.auth.dto.ClaimInviteRequest;
import com.backend.auth.dto.ClaimInviteResponse;
import com.backend.auth.dto.DeviceResponse;
import com.backend.auth.dto.HostInviteResponse;
import com.backend.auth.dto.SessionResponse;
import com.backend.auth.dto.TokenClaimResponse;
import com.backend.auth.dto.UserResponse;
import com.backend.auth.entity.Device;
import com.backend.auth.entity.PairingRequest;
import com.backend.auth.entity.User;
import com.backend.auth.enums.DeviceType;
import com.backend.auth.repository.DeviceRepository;
import com.backend.auth.repository.PairingRequestRepository;
import com.backend.auth.repository.UserRepository;
import com.backend.auth.security.AuthRateLimiter;
import com.backend.session.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final Duration pairingTtl;
    private final Duration pollInterval;

    private final UserRepository users;
    private final DeviceRepository devices;
    private final PairingRequestRepository pairings;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    private final AuthRateLimiter rateLimiter;
    private final SessionService sessionService;

    @Autowired
    public AuthService(UserRepository users,
                       DeviceRepository devices,
                       PairingRequestRepository pairings,
                       org.springframework.security.crypto.password.PasswordEncoder passwordEncoder,
                       @Autowired(required = false) AuthRateLimiter rateLimiter,
                       @Autowired(required = false) SessionService sessionService,
                       @Value("${teilen.auth.pairing-ttl-seconds:300}") long pairingTtlSeconds,
                       @Value("${teilen.auth.poll-interval-seconds:3}") long pollIntervalSeconds) {
        this.users = users;
        this.devices = devices;
        this.pairings = pairings;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter != null ? rateLimiter : new AuthRateLimiter();
        this.sessionService = sessionService;
        this.pairingTtl = Duration.ofSeconds(pairingTtlSeconds);
        this.pollInterval = Duration.ofSeconds(pollIntervalSeconds);
    }

    public AuthService(UserRepository users,
                       DeviceRepository devices,
                       PairingRequestRepository pairings,
                       org.springframework.security.crypto.password.PasswordEncoder passwordEncoder,
                       long pairingTtlSeconds,
                       long pollIntervalSeconds) {
        this(users, devices, pairings, passwordEncoder, null, null, pairingTtlSeconds, pollIntervalSeconds);
    }

    // ------------------------------------------------------------------ accounts

    @Transactional
    public SessionResponse createAccount(String rawUsername, String password, String displayName,
                                         String rawDeviceName, String requestedType) {
        return createAccount(rawUsername, password, displayName, rawDeviceName, requestedType, null, null, null);
    }

    @Transactional
    public SessionResponse createAccount(String rawUsername, String password, String displayName,
                                         String rawDeviceName, String requestedType,
                                         String devicePublicKey, String ipAddress, String platform) {
        String rateKey = "create:" + (ipAddress != null ? ipAddress : "local");
        rateLimiter.checkAllowed(rateKey);

        String username = requireUsername(rawUsername);
        if (password == null || password.isBlank() || password.length() < 6) {
            rateLimiter.recordFailure(rateKey);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "password must be at least 6 characters");
        }
        if (users.existsByUsernameIgnoreCase(username)) {
            rateLimiter.recordFailure(rateKey);
            throw new com.backend.auth.exception.UsernameTakenException();
        }

        Instant now = Instant.now();
        String passwordHash = passwordEncoder.encode(password);
        User user = new User(UUID.randomUUID(), username, clean(displayName, 80), passwordHash, now);
        try {
            users.save(user);
        } catch (DataIntegrityViolationException e) {
            rateLimiter.recordFailure(rateKey);
            throw new com.backend.auth.exception.UsernameTakenException();
        }

        if (sessionService != null) {
            sessionService.getOrCreatePersonalSession(user.getId());
        }

        DeviceType type = requestedType != null ? DeviceType.parse(requestedType) : DeviceType.PHONE;
        SessionResponse session = pairDevice(user, type, rawDeviceName, devicePublicKey, ipAddress, platform, now);
        rateLimiter.recordSuccess(rateKey);
        log.info("created account {} and paired device {}", username, session.deviceName());
        return session;
    }

    @Transactional
    public SessionResponse login(String rawUsername, String password, String rawDeviceName, String requestedType) {
        return login(rawUsername, password, rawDeviceName, requestedType, null, null, null);
    }

    @Transactional
    public SessionResponse login(String rawUsername, String password, String rawDeviceName, String requestedType,
                                 String devicePublicKey, String ipAddress, String platform) {
        String rateKey = "login:" + (ipAddress != null ? ipAddress : "local") + ":" + String.valueOf(rawUsername).toLowerCase();
        rateLimiter.checkAllowed(rateKey);

        String username = requireUsername(rawUsername);
        if (password == null || password.isBlank()) {
            rateLimiter.recordFailure(rateKey);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "password must not be blank");
        }
        User user = users.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> {
                    rateLimiter.recordFailure(rateKey);
                    return new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                            "invalid_credentials: username or password incorrect");
                });
        if (user.getPasswordHash() == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            rateLimiter.recordFailure(rateKey);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "invalid_credentials: username or password incorrect");
        }

        if (sessionService != null) {
            sessionService.getOrCreatePersonalSession(user.getId());
        }

        Instant now = Instant.now();
        DeviceType type = requestedType != null ? DeviceType.parse(requestedType) : DeviceType.PHONE;
        SessionResponse session = pairDevice(user, type, rawDeviceName, devicePublicKey, ipAddress, platform, now);
        rateLimiter.recordSuccess(rateKey);
        log.info("logged in account {} on device {}", username, session.deviceName());
        return session;
    }

    @Transactional
    public SessionResponse refreshToken(String currentToken, String ipAddress) {
        if (currentToken == null || currentToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing_session_token");
        }
        Device device = devices.findByTokenHash(Tokens.hash(currentToken))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_or_expired_session"));

        User user = findUser(device.getUserId());
        String newToken = Tokens.newToken();
        device.setTokenHash(Tokens.hash(newToken));
        if (ipAddress != null && !ipAddress.isBlank()) {
            device.setIpAddress(clean(ipAddress, 64));
        }
        Instant now = Instant.now();
        device.setDevicePublicKey(device.getDevicePublicKey());
        devices.save(device);

        log.info("Rotated token for device {} of user {}", device.getName(), user.getUsername());
        return new SessionResponse(newToken, device.getId(), device.getName(), UserResponse.from(user));
    }

    @Transactional(readOnly = true)
    public UserResponse me(UUID userId) {
        return UserResponse.from(findUser(userId));
    }

    // ------------------------------------------------------------------ pairing

    @Transactional
    public PairingStartResponse startPairing(String rawDeviceName, String requestedType, String platform,
                                             String publicKey) {
        return startPairing(rawDeviceName, requestedType, platform, publicKey, null);
    }

    @Transactional
    public PairingStartResponse startPairing(String rawDeviceName, String requestedType, String platform,
                                             String publicKey, String devicePublicKey) {
        DeviceType type = DeviceType.parse(requestedType);
        Instant now = Instant.now();

        String deviceCode = Tokens.newToken();
        PairingRequest request = null;
        String userCode = null;
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
                request.setNewcomerPublicKey(clean(publicKey, 200));
                request.setDevicePublicKey(clean(devicePublicKey, 200));
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

    @Transactional
    public HostInviteResponse createHostInvite(UUID userId, String encryptedKeyBundle,
                                               String requestedUserCode,
                                               String rawDeviceName, String platform) {
        Instant now = Instant.now();
        User owner = findUser(userId);

        String deviceCode = Tokens.newToken();
        PairingRequest request = null;
        String userCode = null;

        if (requestedUserCode != null && !requestedUserCode.isBlank()) {
            userCode = Tokens.normalizeUserCode(requestedUserCode);
            if (userCode.length() == 8) {
                request = new PairingRequest(UUID.randomUUID(),
                        Tokens.hash(deviceCode),
                        Tokens.hash(userCode),
                        clean(rawDeviceName != null && !rawDeviceName.isBlank() ? rawDeviceName : owner.getUsername(), 120),
                        DeviceType.PHONE,
                        clean(platform, 160),
                        now,
                        now.plus(pairingTtl));
                request.setUserId(owner.getId());
                request.setHostInvite(true);
                request.setKeyPackage(clean(encryptedKeyBundle, 16000));
                try {
                    pairings.save(request);
                } catch (DataIntegrityViolationException e) {
                    log.debug("provided pairing code {} already taken, retrying random", userCode);
                    request = null;
                }
            }
        }

        if (request == null) {
            for (int attempt = 0; attempt < 6; attempt++) {
                userCode = Tokens.newUserCode();
                try {
                    request = new PairingRequest(UUID.randomUUID(),
                            Tokens.hash(deviceCode),
                            Tokens.hash(userCode),
                            clean(rawDeviceName != null && !rawDeviceName.isBlank() ? rawDeviceName : owner.getUsername(), 120),
                            DeviceType.PHONE,
                            clean(platform, 160),
                            now,
                            now.plus(pairingTtl));
                    request.setUserId(owner.getId());
                    request.setHostInvite(true);
                    request.setKeyPackage(clean(encryptedKeyBundle, 16000));
                    pairings.save(request);
                    break;
                } catch (DataIntegrityViolationException e) {
                    log.debug("pairing code {} already taken, retrying", userCode);
                }
            }
        }
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "could not find a free pairing code, try again");
        }

        String formatted = Tokens.formatUserCode(userCode);
        return new HostInviteResponse(
                formatted,
                deviceCode,
                "/api/auth/device/qr.svg?code=" + formatted,
                pairingTtl.toSeconds(),
                request.getExpiresAt()
        );
    }

    @Transactional
    public ClaimInviteResponse claimHostInvite(ClaimInviteRequest requestBody) {
        Instant now = Instant.now();
        String userCode = Tokens.normalizeUserCode(requestBody.userCode());
        PairingRequest request = findPairingByUserCode(userCode);

        if (request.isExpired(now)) {
            throw new ResponseStatusException(HttpStatus.GONE, "pairing_expired");
        }
        if (!request.isPending()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "pairing_already_resolved");
        }
        if (!request.isHostInvite() || request.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "not_a_host_invite");
        }

        User owner = findUser(request.getUserId());
        String token = Tokens.newToken();
        UUID newDeviceId = UUID.randomUUID();
        DeviceType type = DeviceType.parse(requestBody.deviceType());
        String devName = clean(requestBody.deviceName() != null && !requestBody.deviceName().isBlank()
                ? requestBody.deviceName() : "Companion Device", 120);

        Device device = new Device(newDeviceId, owner.getId(), type, devName,
                Tokens.hash(token), clean(requestBody.devicePublicKey(), 200), null,
                clean(requestBody.platform(), 160), now);
        devices.save(device);

        request.approve(device.getId(), token, now);
        request.deliver(now);
        pairings.save(request);

        log.info("device {} claimed host invite into account {}", devName, owner.getUsername());

        return new ClaimInviteResponse(
                token,
                device.getId(),
                device.getName(),
                owner.getId(),
                owner.getUsername(),
                request.getKeyPackage()
        );
    }

    @Transactional
    public ApprovalResponse approve(UUID userId, String rawUserCode, String keyPackage) {
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
                request.getDeviceName(), Tokens.hash(token), request.getDevicePublicKey(),
                null, request.getPlatform(), now);
        devices.save(device);
        request.approve(device.getId(), token, now);
        request.setKeyPackage(clean(keyPackage, 4000));

        log.info("{} approved a {} device: {}", request.describe(), request.getDeviceType(),
                request.getDeviceName());
        return new ApprovalResponse(request.getDeviceName(), request.getDeviceType(),
                request.describe(), request.getExpiresAt());
    }

    @Transactional
    public void deny(UUID userId, String rawUserCode) {
        PairingRequest request = findPairingByUserCode(rawUserCode);
        if (!request.isPending()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "pairing_already_resolved");
        }
        request.deny(Instant.now());
        log.info("{} denied a pairing request from {}", findUser(userId).label(), request.getDeviceName());
    }

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
                    return null;
                }
                case DENIED -> throw new ResponseStatusException(HttpStatus.FORBIDDEN, "pairing_denied");
                default -> throw new ResponseStatusException(HttpStatus.GONE,
                        "pairing_" + request.getStatus().name().toLowerCase());
            }
        }

        String token = request.deliver(now);
        Device device = devices.findById(request.getDeviceId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE, "pairing_gone"));
        User user = findUser(device.getUserId());
        log.info("paired {} device {} for {}", request.getDeviceType(), device.getName(), user.label());
        return new TokenClaimResponse(token, device.getId(), device.getDeviceType(), device.getName(),
                request.getKeyPackage(), UserResponse.from(user));
    }

    @Transactional(readOnly = true)
    public PairingRequest pendingByCode(String rawUserCode) {
        String rateKey = "pending:" + rawUserCode;
        rateLimiter.checkAllowed(rateKey);
        try {
            PairingRequest request = findPairingByUserCode(rawUserCode);
            if (!request.isPending() || request.isExpired(Instant.now())) {
                rateLimiter.recordFailure(rateKey);
                throw new ResponseStatusException(HttpStatus.GONE, "pairing_expired");
            }
            rateLimiter.recordSuccess(rateKey);
            return request;
        } catch (ResponseStatusException e) {
            rateLimiter.recordFailure(rateKey);
            throw e;
        }
    }

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

    @Transactional
    public void revokeOtherDevices(UUID userId, UUID currentDeviceId) {
        devices.deleteByUserIdAndIdNot(userId, currentDeviceId);
        log.info("Revoked all other devices for user {} except {}", userId, currentDeviceId);
    }

    @Transactional
    public void touch(UUID deviceId) {
        Instant now = Instant.now();
        devices.touch(deviceId, now, now.minus(Duration.ofMinutes(5)));
    }

    @Transactional
    public void updateDevicePublicKey(UUID deviceId, String devicePublicKey) {
        if (deviceId == null || devicePublicKey == null || devicePublicKey.isBlank()) {
            return;
        }
        devices.findById(deviceId).ifPresent(device -> {
            device.setDevicePublicKey(clean(devicePublicKey, 200));
            devices.save(device);
            log.info("Updated device public key for device {}", deviceId);
        });
    }

    // ------------------------------------------------------------------ helpers

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
        return new AuthenticatedUser(user.getId(), device.getId(), user.getUsername(), device.getDevicePublicKey());
    }

    private SessionResponse pairDevice(User user, DeviceType type, String rawDeviceName,
                                       String devicePublicKey, String ipAddress, String platform, Instant now) {
        String name = clean(rawDeviceName, 120);
        if (name.isBlank()) {
            name = type == DeviceType.PHONE ? "phone" : "browser";
        }
        String token = Tokens.newToken();
        Device device = new Device(UUID.randomUUID(), user.getId(), type, name, Tokens.hash(token),
                clean(devicePublicKey, 200), clean(ipAddress, 64), clean(platform, 160), now);
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

    private String requireUsername(String raw) {
        if (raw == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Username must be 3–32 characters and contain only letters and numbers (no spaces or special characters).");
        }
        String username = raw.strip();
        if (!username.matches("^[a-zA-Z0-9]{3,32}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Username must be 3–32 characters and contain only letters and numbers (no spaces or special characters).");
        }
        return username;
    }

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

    public static String pairingUri(String rawUserCode) {
        return "teilen://pair?code=" + Tokens.formatUserCode(rawUserCode);
    }
}

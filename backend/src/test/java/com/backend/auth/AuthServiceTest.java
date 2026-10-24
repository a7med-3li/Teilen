package com.backend.auth;

import com.backend.auth.dto.SessionResponse;
import com.backend.auth.entity.Device;
import com.backend.auth.entity.User;
import com.backend.auth.exception.UsernameTakenException;
import com.backend.auth.repository.DeviceRepository;
import com.backend.auth.repository.PairingRequestRepository;
import com.backend.auth.repository.UserRepository;
import com.backend.auth.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository users;
    @Mock
    private DeviceRepository devices;
    @Mock
    private PairingRequestRepository pairings;

    private PasswordEncoder passwordEncoder;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        authService = new AuthService(users, devices, pairings, passwordEncoder, 300, 3);
    }

    @Test
    void createAccountHashesPasswordAndPairsDevice() {
        when(users.existsByUsernameIgnoreCase("ahmed123")).thenReturn(false);

        SessionResponse session = authService.createAccount(
                "ahmed123", "securePassword123", "Ahmed", "My Phone", "PHONE");

        assertNotNull(session);
        assertNotNull(session.token());
        assertEquals("My Phone", session.deviceName());

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(users).save(userCaptor.capture());
        User savedUser = userCaptor.getValue();
        assertEquals("ahmed123", savedUser.getUsername());
        assertEquals("Ahmed", savedUser.getDisplayName());
        assertTrue(passwordEncoder.matches("securePassword123", savedUser.getPasswordHash()));

        verify(devices).save(any(Device.class));
    }

    @Test
    void createAccountRejectsBlankOrShortPassword() {
        assertThrows(ResponseStatusException.class, () ->
                authService.createAccount("ahmed123", "12345", "Ahmed", "My Phone", "PHONE"));

        assertThrows(ResponseStatusException.class, () ->
                authService.createAccount("ahmed123", "", "Ahmed", "My Phone", "PHONE"));
    }

    @Test
    void createAccountRejectsInvalidUsername() {
        ResponseStatusException ex1 = assertThrows(ResponseStatusException.class, () ->
                authService.createAccount("ah", "securePassword123", "Ahmed", "My Phone", "PHONE"));
        assertEquals("Username must be 3–32 characters and contain only letters and numbers (no spaces or special characters).", ex1.getReason());

        ResponseStatusException ex2 = assertThrows(ResponseStatusException.class, () ->
                authService.createAccount("ahmed@123", "securePassword123", "Ahmed", "My Phone", "PHONE"));
        assertEquals("Username must be 3–32 characters and contain only letters and numbers (no spaces or special characters).", ex2.getReason());

        ResponseStatusException ex3 = assertThrows(ResponseStatusException.class, () ->
                authService.createAccount("+1234567890", "securePassword123", "Ahmed", "My Phone", "PHONE"));
        assertEquals("Username must be 3–32 characters and contain only letters and numbers (no spaces or special characters).", ex3.getReason());
    }

    @Test
    void createAccountRejectsDuplicateUsername() {
        when(users.existsByUsernameIgnoreCase("ahmed123")).thenReturn(true);

        assertThrows(UsernameTakenException.class, () ->
                authService.createAccount("ahmed123", "securePassword123", "Ahmed", "My Phone", "PHONE"));
    }

    @Test
    void loginSucceedsWithCorrectPassword() {
        String username = "ahmed123";
        String rawPassword = "correctPassword";
        String encoded = passwordEncoder.encode(rawPassword);
        User user = new User(UUID.randomUUID(), username, "Ahmed", encoded, Instant.now());

        when(users.findByUsernameIgnoreCase(username)).thenReturn(Optional.of(user));

        SessionResponse session = authService.login(username, rawPassword, "New Device", "PHONE");
        assertNotNull(session);
        assertNotNull(session.token());
        verify(devices).save(any(Device.class));
    }

    @Test
    void loginFailsWithWrongPassword() {
        String username = "ahmed123";
        String encoded = passwordEncoder.encode("correctPassword");
        User user = new User(UUID.randomUUID(), username, "Ahmed", encoded, Instant.now());

        when(users.findByUsernameIgnoreCase(username)).thenReturn(Optional.of(user));

        assertThrows(ResponseStatusException.class, () ->
                authService.login(username, "wrongPassword", "New Device", "PHONE"));
    }

    @Test
    void loginFailsForUnknownUser() {
        when(users.findByUsernameIgnoreCase("unknownuser")).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class, () ->
                authService.login("unknownuser", "anyPassword", "New Device", "PHONE"));
    }

    @Test
    void createAndClaimHostInvite() {
        UUID userId = UUID.randomUUID();
        User user = new User(userId, "ahmed123", "Ahmed", "hash", Instant.now());
        when(users.findById(userId)).thenReturn(Optional.of(user));

        com.backend.auth.dto.HostInviteResponse invite = authService.createHostInvite(
                userId, "{\"wrapped\":\"bundle\"}", "RC689N4S", "Web Browser", "Web"
        );

        assertNotNull(invite);
        assertEquals("RC68-9N4S", invite.userCode());

        ArgumentCaptor<com.backend.auth.entity.PairingRequest> captor =
                ArgumentCaptor.forClass(com.backend.auth.entity.PairingRequest.class);
        verify(pairings).save(captor.capture());
        com.backend.auth.entity.PairingRequest savedRequest = captor.getValue();
        assertTrue(savedRequest.isHostInvite());
        assertEquals("{\"wrapped\":\"bundle\"}", savedRequest.getKeyPackage());

        when(pairings.findByUserCodeHash(anyString())).thenReturn(Optional.of(savedRequest));

        com.backend.auth.dto.ClaimInviteResponse claim = authService.claimHostInvite(
                new com.backend.auth.dto.ClaimInviteRequest("RC68-9N4S", "Phone Device", "PHONE", null, "Android")
        );

        assertNotNull(claim);
        assertNotNull(claim.token());
        assertEquals("{\"wrapped\":\"bundle\"}", claim.encryptedKeyBundle());
        assertEquals("ahmed123", claim.username());
        assertEquals(userId, claim.userId());
        verify(devices).save(any(Device.class));
    }

    @Test
    void singlePairingFlowGeneratesCodeAndPairsSymmetrically() {
        UUID userAId = UUID.randomUUID();
        UUID userBId = UUID.randomUUID();
        UUID deviceBId = UUID.randomUUID();

        // Device A generates code
        com.backend.auth.dto.DeviceCodeRequest codeReq = new com.backend.auth.dto.DeviceCodeRequest(
                "Browser on Linux", "WEB", "keyPackageA", "fp-device-a"
        );
        com.backend.auth.dto.DeviceCodeResponse codeResp = authService.generateDeviceCode(
                userAId, codeReq, "127.0.0.1", "Firefox"
        );
        assertNotNull(codeResp);
        assertNotNull(codeResp.userCode());
        assertTrue(codeResp.qrSvgUrl().contains(codeResp.userCode()));

        ArgumentCaptor<com.backend.auth.entity.PairingRequest> captor =
                ArgumentCaptor.forClass(com.backend.auth.entity.PairingRequest.class);
        verify(pairings).save(captor.capture());
        com.backend.auth.entity.PairingRequest pr = captor.getValue();
        assertEquals(userAId, pr.getUserId());
        assertEquals("keyPackageA", pr.getKeyPackage());

        when(pairings.findByUserCodeHash(anyString())).thenReturn(Optional.of(pr));

        Device deviceB = new Device(deviceBId, userBId, com.backend.auth.enums.DeviceType.PHONE,
                "Pixel Phone", "tokenHashB", "keyPubB", "127.0.0.1", "Android", "fp-device-b", Instant.now());
        when(devices.findById(deviceBId)).thenReturn(Optional.of(deviceB));

        // Device B pairs
        com.backend.auth.dto.DevicePairRequest pairReq = new com.backend.auth.dto.DevicePairRequest(
                codeResp.userCode(), "scannerKeyB", "Pixel Phone", "PHONE", "fp-device-b"
        );
        com.backend.auth.dto.DevicePairResponse pairResp = authService.pairDeviceWithCode(
                userBId, deviceBId, "callerTokenB", pairReq, "127.0.0.1", "Android"
        );

        assertNotNull(pairResp);
        assertEquals(userAId, pairResp.userId());
        assertEquals("keyPackageA", pairResp.masterKey());
        assertEquals("callerTokenB", pairResp.token());
        assertEquals(userAId, deviceB.getUserId());
    }

    @Test
    void deviceDeduplicationUpdatesExistingDeviceOnFingerprintMatch() {
        UUID userId = UUID.randomUUID();
        String fp = "perm-uuid-browser-123";
        Device existing = new Device(UUID.randomUUID(), userId, com.backend.auth.enums.DeviceType.WEB,
                "Firefox", "oldTokenHash", null, "127.0.0.1", "Linux", fp, Instant.now().minusSeconds(100));

        when(devices.findFirstByUserIdAndClientFingerprint(userId, fp)).thenReturn(Optional.of(existing));

        String hash = passwordEncoder.encode("myPassword");
        User user = new User(userId, "ahmed123", "Ahmed", hash, Instant.now());
        when(users.findByUsernameIgnoreCase("ahmed123")).thenReturn(Optional.of(user));

        SessionResponse session = authService.login(
                "ahmed123", "myPassword", "Firefox", "WEB", null, "127.0.0.1", "Linux", fp
        );

        assertNotNull(session);
        assertEquals(existing.getId(), session.deviceId());
        assertEquals(existing.getName(), session.deviceName());
        verify(devices).save(existing);
    }
}

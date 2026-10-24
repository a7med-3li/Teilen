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
}

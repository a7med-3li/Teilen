package com.backend.auth;

import com.backend.auth.dto.SessionResponse;
import com.backend.auth.entity.Device;
import com.backend.auth.entity.User;
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
        when(users.findByPhone("+1234567890")).thenReturn(Optional.empty());

        SessionResponse session = authService.createAccount(
                "+1234567890", "securePassword123", "Ahmed", "My Phone", "PHONE");

        assertNotNull(session);
        assertNotNull(session.token());
        assertEquals("My Phone", session.deviceName());

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(users).save(userCaptor.capture());
        User savedUser = userCaptor.getValue();
        assertEquals("+1234567890", savedUser.getPhone());
        assertEquals("Ahmed", savedUser.getDisplayName());
        assertTrue(passwordEncoder.matches("securePassword123", savedUser.getPasswordHash()));

        verify(devices).save(any(Device.class));
    }

    @Test
    void createAccountRejectsBlankOrShortPassword() {
        assertThrows(ResponseStatusException.class, () ->
                authService.createAccount("+1234567890", "12345", "Ahmed", "My Phone", "PHONE"));

        assertThrows(ResponseStatusException.class, () ->
                authService.createAccount("+1234567890", "", "Ahmed", "My Phone", "PHONE"));
    }

    @Test
    void loginSucceedsWithCorrectPassword() {
        String phone = "+1234567890";
        String rawPassword = "correctPassword";
        String encoded = passwordEncoder.encode(rawPassword);
        User user = new User(UUID.randomUUID(), phone, "Ahmed", encoded, Instant.now());

        when(users.findByPhone(phone)).thenReturn(Optional.of(user));

        SessionResponse session = authService.login(phone, rawPassword, "New Device", "PHONE");
        assertNotNull(session);
        assertNotNull(session.token());
        verify(devices).save(any(Device.class));
    }

    @Test
    void loginFailsWithWrongPassword() {
        String phone = "+1234567890";
        String encoded = passwordEncoder.encode("correctPassword");
        User user = new User(UUID.randomUUID(), phone, "Ahmed", encoded, Instant.now());

        when(users.findByPhone(phone)).thenReturn(Optional.of(user));

        assertThrows(ResponseStatusException.class, () ->
                authService.login(phone, "wrongPassword", "New Device", "PHONE"));
    }

    @Test
    void loginFailsForUnknownUser() {
        when(users.findByPhone("+19999999999")).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class, () ->
                authService.login("+19999999999", "anyPassword", "New Device", "PHONE"));
    }
}

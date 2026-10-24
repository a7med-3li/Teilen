package com.backend.auth;

import com.backend.auth.entity.User;
import com.backend.auth.repository.UserRepository;
import com.backend.auth.service.VaultMergeService;
import com.backend.item.ShareItemWebSocketHandler;
import com.backend.session.Session;
import com.backend.session.SessionService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VaultMergeServiceTest {

    @Mock
    private EntityManager entityManager;
    @Mock
    private UserRepository userRepository;
    @Mock
    private SessionService sessionService;
    @Mock
    private ShareItemWebSocketHandler webSocketHandler;
    @Mock
    private Query mockQuery;

    private VaultMergeService vaultMergeService;

    @BeforeEach
    void setUp() {
        vaultMergeService = new VaultMergeService(entityManager, userRepository, sessionService, webSocketHandler);
    }

    @Test
    void isAnonymousDetectsVaultPrefix() {
        User anon1 = new User(UUID.randomUUID(), "vault-3k8s9p", "Anonymous", "hash", Instant.now());
        User anon2 = new User(UUID.randomUUID(), "vault12345", "Anonymous", "hash", Instant.now());
        User named = new User(UUID.randomUUID(), "ahmed123", "Ahmed", "hash", Instant.now());

        assertTrue(VaultMergeService.isAnonymous(anon1));
        assertTrue(VaultMergeService.isAnonymous(anon2));
        assertFalse(VaultMergeService.isAnonymous(named));
        assertTrue(VaultMergeService.isAnonymous(null));
    }

    @Test
    void mergePreservesNamedAccountOverAnonymous() {
        UUID anonId = UUID.randomUUID();
        UUID namedId = UUID.randomUUID();
        User anonUser = new User(anonId, "vault-9x2m4k", "Anon", "hash", Instant.now());
        User namedUser = new User(namedId, "superahmed", "Ahmed", "hash", Instant.now());

        when(userRepository.findById(anonId)).thenReturn(Optional.of(anonUser));
        when(userRepository.findById(namedId)).thenReturn(Optional.of(namedUser));

        Session primaryPersonalSession = new Session(UUID.randomUUID(), "Personal", namedId, null, true, Instant.now());
        when(sessionService.getOrCreatePersonalSession(namedId)).thenReturn(primaryPersonalSession);

        when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);
        when(mockQuery.setParameter(anyString(), any())).thenReturn(mockQuery);
        when(mockQuery.executeUpdate()).thenReturn(1);

        // Anon is host, named is scanner
        VaultMergeService.MergeResult res = vaultMergeService.mergeIfDifferent(anonId, namedId, true);

        assertEquals(namedId, res.primaryUserId());
        assertEquals(anonId, res.secondaryUserId());
        assertTrue(res.merged());

        verify(webSocketHandler).broadcast(eq(namedId), any());
        verify(webSocketHandler).broadcast(eq(anonId), any());
    }

    @Test
    void mergeDefaultsToHostWhenBothAreAnonymous() {
        UUID hostId = UUID.randomUUID();
        UUID scannerId = UUID.randomUUID();
        User hostUser = new User(hostId, "vault-host12", "Anon Host", "hash", Instant.now());
        User scannerUser = new User(scannerId, "vault-scan34", "Anon Scanner", "hash", Instant.now());

        when(userRepository.findById(hostId)).thenReturn(Optional.of(hostUser));
        when(userRepository.findById(scannerId)).thenReturn(Optional.of(scannerUser));

        Session primaryPersonalSession = new Session(UUID.randomUUID(), "Personal", hostId, null, true, Instant.now());
        when(sessionService.getOrCreatePersonalSession(hostId)).thenReturn(primaryPersonalSession);

        when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);
        when(mockQuery.setParameter(anyString(), any())).thenReturn(mockQuery);
        when(mockQuery.executeUpdate()).thenReturn(1);

        VaultMergeService.MergeResult res = vaultMergeService.mergeIfDifferent(hostId, scannerId, true);

        assertEquals(hostId, res.primaryUserId());
        assertEquals(scannerId, res.secondaryUserId());
        assertTrue(res.merged());
    }
}

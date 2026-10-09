package com.backend.auth;

import com.backend.auth.entity.PairingRequest;
import com.backend.auth.enums.DeviceType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The key-exchange columns are a relay, not a feature: whatever the newcomer offers and the
 * approver seals has to survive the trip to the newcomer untouched, and a pairing that never uses
 * them has to behave exactly as before.
 */
class PairingKeyPackageTest {

    private PairingRequest request() {
        Instant now = Instant.now();
        return new PairingRequest(UUID.randomUUID(), "device-hash", "code-hash", "Chrome on Linux",
                DeviceType.WEB, "linux", now, now.plusSeconds(300));
    }

    @Test
    void theNewcomersPublicKeyIsStoredButOptional() {
        PairingRequest request = request();
        assertThat(request.getNewcomerPublicKey()).isNull();

        request.setNewcomerPublicKey("cHVibGljLWtleQ==");
        assertThat(request.getNewcomerPublicKey()).isEqualTo("cHVibGljLWtleQ==");
    }

    @Test
    void anApprovedPairingRelaysTheSealedPackageToTheNewcomerExactlyOnce() {
        PairingRequest request = request();
        Instant now = Instant.now();

        request.approve(UUID.randomUUID(), "the-token", now);
        request.setKeyPackage("{\"pk\":\"...\",\"nonce\":\"...\",\"wrapped\":\"...\"}");

        assertThat(request.isApproved()).isTrue();
        assertThat(request.getKeyPackage()).contains("wrapped");

        // handing over the token leaves the key package where the newcomer can still read it
        assertThat(request.deliver(now)).isEqualTo("the-token");
        assertThat(request.getKeyPackage()).contains("wrapped");
    }

    @Test
    void pairingsWithoutAKeyExchangeCarryNoPackage() {
        PairingRequest request = request();
        request.approve(UUID.randomUUID(), "the-token", Instant.now());

        assertThat(request.getKeyPackage()).isNull();
        assertThat(request.getNewcomerPublicKey()).isNull();
    }
}

package com.backend.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PairingRequestRepository extends JpaRepository<PairingRequest, UUID> {

    Optional<PairingRequest> findByDeviceCodeHash(String deviceCodeHash);

    Optional<PairingRequest> findByUserCodeHash(String userCodeHash);

    /** anything past its moment is swept, whether it was answered or not */
    long deleteByExpiresAtBefore(Instant cutoff);
}

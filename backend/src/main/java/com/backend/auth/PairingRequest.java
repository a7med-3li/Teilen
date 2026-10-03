package com.backend.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A device asking to join, in the shape of RFC 8628 (device authorization grant).
 *
 * <pre>
 * newcomer → POST /api/auth/device/code   → user code to show, device code to poll with
 * paired   → POST /api/auth/device/approve (with the user code, from a scan or typed in)
 * newcomer → POST /api/auth/device/token  → its token, exactly once
 * </pre>
 *
 * The same flow both ways: a browser shows the QR, a spare phone shows the code as text, and in
 * both cases it is an already-paired device that approves.
 */
@Entity
@Table(name = "pairing_requests")
public class PairingRequest {

    @Id
    private UUID id;

    @Column(name = "device_code_hash", nullable = false, unique = true, length = 64)
    private String deviceCodeHash;

    @Column(name = "user_code_hash", nullable = false, unique = true, length = 64)
    private String userCodeHash;

    @Column(name = "device_name", nullable = false, length = 120)
    private String deviceName;

    @Enumerated(EnumType.STRING)
    @Column(name = "device_type", nullable = false, length = 16)
    private DeviceType deviceType;

    /** user agent / platform, only so the approval prompt can name the newcomer */
    @Column(length = 160)
    private String platform;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PairingStatus status;

    @Column(name = "device_id")
    private UUID deviceId;

    /** the new device's token, held here only until it is collected */
    @Column(name = "token_value", length = 200)
    private String tokenValue;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected PairingRequest() {
        // for JPA
    }

    public PairingRequest(UUID id, String deviceCodeHash, String userCodeHash, String deviceName,
                          DeviceType deviceType, String platform, Instant now, Instant expiresAt) {
        this.id = id;
        this.deviceCodeHash = deviceCodeHash;
        this.userCodeHash = userCodeHash;
        this.deviceName = deviceName;
        this.deviceType = deviceType;
        this.platform = platform;
        this.status = PairingStatus.PENDING;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    public void approve(UUID newDeviceId, String token, Instant now) {
        this.status = PairingStatus.APPROVED;
        this.deviceId = newDeviceId;
        this.tokenValue = token;
        this.resolvedAt = now;
    }

    /** hands the token over exactly once and wipes it from the row */
    public String deliver(Instant now) {
        String token = tokenValue;
        this.status = PairingStatus.DELIVERED;
        this.tokenValue = null;
        this.resolvedAt = now;
        return token;
    }

    public void deny(Instant now) {
        this.status = PairingStatus.DENIED;
        this.tokenValue = null;
        this.resolvedAt = now;
    }

    public boolean isPending() {
        return status == PairingStatus.PENDING;
    }

    public boolean isApproved() {
        return status == PairingStatus.APPROVED;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    /** what the approval prompt shows: "Chrome on Linux" or just "Chrome" */
    public String describe() {
        return platform == null || platform.isBlank() ? deviceName : deviceName + " — " + platform;
    }

    public UUID getId() {
        return id;
    }

    public String getDeviceCodeHash() {
        return deviceCodeHash;
    }

    public String getUserCodeHash() {
        return userCodeHash;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public DeviceType getDeviceType() {
        return deviceType;
    }

    public String getPlatform() {
        return platform;
    }

    public PairingStatus getStatus() {
        return status;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public String getTokenValue() {
        return tokenValue;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}

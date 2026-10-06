package com.backend.auth.entity;

import com.backend.auth.enums.DeviceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One paired app. The bearer token lives in the caller's app, never here: the row keeps its
 * SHA-256 so a database dump is not a set of working credentials.
 */
@Entity
@Table(name = "devices")
public class Device {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "device_type", nullable = false, length = 16)
    private DeviceType deviceType;

    /** whatever the app called itself: "Ahmed's Pixel", "Firefox on Linux" */
    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    /** when it was paired */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    protected Device() {
        // for JPA
    }

    public Device(UUID id, UUID userId, DeviceType deviceType, String name, String tokenHash, Instant now) {
        this.id = id;
        this.userId = userId;
        this.deviceType = deviceType;
        this.name = name;
        this.tokenHash = tokenHash;
        this.createdAt = now;
        this.lastSeenAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public DeviceType getDeviceType() {
        return deviceType;
    }

    public String getName() {
        return name;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }
}

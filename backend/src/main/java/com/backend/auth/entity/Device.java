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

    @Column(name = "device_public_key", length = 200)
    private String devicePublicKey;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "platform", length = 160)
    private String platform;

    protected Device() {
        // for JPA
    }

    public Device(UUID id, UUID userId, DeviceType deviceType, String name, String tokenHash, Instant now) {
        this(id, userId, deviceType, name, tokenHash, null, null, null, now);
    }

    public Device(UUID id, UUID userId, DeviceType deviceType, String name, String tokenHash,
                  String devicePublicKey, String ipAddress, String platform, Instant now) {
        this.id = id;
        this.userId = userId;
        this.deviceType = deviceType;
        this.name = name;
        this.tokenHash = tokenHash;
        this.devicePublicKey = devicePublicKey;
        this.ipAddress = ipAddress;
        this.platform = platform;
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

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public String getDevicePublicKey() {
        return devicePublicKey;
    }

    public void setDevicePublicKey(String devicePublicKey) {
        this.devicePublicKey = devicePublicKey;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }
}

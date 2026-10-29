package com.backend.admin.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "telemetry_events")
public class TelemetryEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false, length = 48)
    private String eventType;

    @Column(name = "item_type", length = 24)
    private String itemType;

    @Column(name = "payload_bytes", nullable = false)
    private Long payloadBytes = 0L;

    @Column(name = "ttl_seconds")
    private Integer ttlSeconds;

    @Column(name = "platform", length = 24)
    private String platform;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TelemetryEvent() {
    }

    public TelemetryEvent(String eventType, String itemType, Long payloadBytes, Integer ttlSeconds, String platform, Instant createdAt) {
        this.eventType = eventType;
        this.itemType = itemType;
        this.payloadBytes = payloadBytes != null ? payloadBytes : 0L;
        this.ttlSeconds = ttlSeconds;
        this.platform = platform;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public String getItemType() {
        return itemType;
    }

    public Long getPayloadBytes() {
        return payloadBytes;
    }

    public Integer getTtlSeconds() {
        return ttlSeconds;
    }

    public String getPlatform() {
        return platform;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

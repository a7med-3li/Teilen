package com.backend.item;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "share_items")
public class ShareItem {

    @Id
    private UUID id;

    /** whose feed this belongs to; the only thing that decides who may see it */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ShareItemType type;

    /**
     * Inline text or URL. For blob items this holds the original file name, so the feed can
     * show something meaningful without a second lookup.
     */
    @Column(nullable = false, columnDefinition = "text")
    private String content;

    /** file name on disk, null for text and links */
    @Column(name = "storage_ref", length = 255)
    private String storageRef;

    @Column(name = "mime_type", length = 128)
    private String mimeType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected ShareItem() {
        // for JPA
    }

    public ShareItem(UUID id, UUID userId, ShareItemType type, String content,
                     Instant createdAt, Instant expiresAt) {
        this(id, userId, type, content, null, null, null, createdAt, expiresAt);
    }

    public ShareItem(UUID id, UUID userId, ShareItemType type, String content, String storageRef,
                     String mimeType, Long sizeBytes, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.userId = userId;
        this.type = type;
        this.content = content;
        this.storageRef = storageRef;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean hasBlob() {
        return storageRef != null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public ShareItemType getType() {
        return type;
    }

    public String getContent() {
        return content;
    }

    public String getStorageRef() {
        return storageRef;
    }

    public String getMimeType() {
        return mimeType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}

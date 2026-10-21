package com.backend.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sessions")
public class Session {

    @Id
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Column(name = "invite_code_hash", length = 64, unique = true)
    private String inviteCodeHash;

    @Column(name = "is_personal", nullable = false)
    private boolean isPersonal;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Session() {
    }

    public Session(UUID id, String name, UUID ownerUserId, String inviteCodeHash, boolean isPersonal, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.ownerUserId = ownerUserId;
        this.inviteCodeHash = inviteCodeHash;
        this.isPersonal = isPersonal;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public String getInviteCodeHash() {
        return inviteCodeHash;
    }

    public void setInviteCodeHash(String inviteCodeHash) {
        this.inviteCodeHash = inviteCodeHash;
    }

    public boolean isPersonal() {
        return isPersonal;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

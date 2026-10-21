package com.backend.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "session_members")
public class SessionMember {

    @Id
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SessionRole role;

    @Column(name = "encrypted_session_key", columnDefinition = "text")
    private String encryptedSessionKey;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    protected SessionMember() {
    }

    public SessionMember(UUID id, UUID sessionId, UUID userId, SessionRole role, String encryptedSessionKey, Instant joinedAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.userId = userId;
        this.role = role;
        this.encryptedSessionKey = encryptedSessionKey;
        this.joinedAt = joinedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public UUID getUserId() {
        return userId;
    }

    public SessionRole getRole() {
        return role;
    }

    public void setRole(SessionRole role) {
        this.role = role;
    }

    public String getEncryptedSessionKey() {
        return encryptedSessionKey;
    }

    public void setEncryptedSessionKey(String encryptedSessionKey) {
        this.encryptedSessionKey = encryptedSessionKey;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }
}

package com.backend.auth.entity;

import com.backend.auth.service.AuthService;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A person. Identity is a phone number and nothing else yet: there is no OTP, so creating an
 * account proves no ownership of the number — see {@link AuthService#createAccount}.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    private UUID id;

    /** normalised: an optional leading '+' and digits only */
    @Column(nullable = false, unique = true, length = 32)
    private String phone;

    @Column(name = "display_name", length = 80)
    private String displayName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected User() {
        // for JPA
    }

    public User(UUID id, String phone, String displayName, Instant createdAt) {
        this.id = id;
        this.phone = phone;
        this.displayName = displayName;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getPhone() {
        return phone;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** "Ahmed" if they gave a name, otherwise the number itself */
    public String label() {
        return displayName == null || displayName.isBlank() ? phone : displayName;
    }
}

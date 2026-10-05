package com.sahanswitch.participant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.EntityListeners;

@Entity
@Table(name = "participants")
@EntityListeners(AuditingEntityListener.class)
public class Participant {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ParticipantType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ParticipantStatus status;

    /**
     * Task 1: SHA-256 hash of the participant's API key (clear text is never stored). Null until
     * an administrator issues a key; a participant without a key cannot use the API-key login.
     */
    @Column(name = "api_key_hash", length = 255)
    private String apiKeyHash;

    /** Task 1: comma-separated roles the participant's API key acts with. */
    @Column(name = "allowed_roles", nullable = false, length = 100)
    private String allowedRoles = "PARTICIPANT";

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Participant() {
        // Required by JPA
    }

    public Participant(
            String code,
            String name,
            ParticipantType type,
            ParticipantStatus status
    ) {
        this.code = code;
        this.name = name;
        this.type = type;
        this.status = status;
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public ParticipantType getType() {
        return type;
    }

    public ParticipantStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getApiKeyHash() {
        return apiKeyHash;
    }

    public String getAllowedRoles() {
        return allowedRoles;
    }

    public boolean hasApiKey() {
        return apiKeyHash != null;
    }

    /** Stores the hash of a freshly issued key. Issuing again replaces (rotates) the old key. */
    public void assignApiKeyHash(String apiKeyHash) {
        this.apiKeyHash = apiKeyHash;
    }

    public void deactivate() {
        this.status = ParticipantStatus.INACTIVE;
    }

    public boolean isActive() {
        return this.status == ParticipantStatus.ACTIVE;
    }
}

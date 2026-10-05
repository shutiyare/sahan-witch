package com.sahanswitch.security.user;

import com.sahanswitch.participant.domain.Participant;
import com.sahanswitch.security.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Task 1: a person who signs in to the operations portal with a username and password.
 *
 * <p>An {@link Role#ADMIN} belongs to the switch operator and has no participant; a
 * {@link Role#PARTICIPANT} user always belongs to exactly one participant (enforced by a
 * database check constraint as well).
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private Role role;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "participant_id")
    private Participant participant;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected User() {
        // Required by JPA
    }

    public User(String username, String passwordHash, Role role, Participant participant) {
        if (role == Role.PARTICIPANT && participant == null) {
            throw new IllegalArgumentException("A participant user must belong to a participant");
        }
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.participant = participant;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public Participant getParticipant() {
        return participant;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

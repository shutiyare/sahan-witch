package com.sahanswitch.participant.infrastructure;

import com.sahanswitch.participant.domain.Participant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ParticipantRepository extends JpaRepository<Participant, UUID> {

    boolean existsByCode(String code);
    Optional<Participant> findByCode(String code);

    /** Task 1: API-key authentication looks the caller up by the hash of the presented key. */
    Optional<Participant> findByApiKeyHash(String apiKeyHash);

    long countByStatus(com.sahanswitch.participant.domain.ParticipantStatus status);

    java.util.List<Participant> findByStatus(com.sahanswitch.participant.domain.ParticipantStatus status);
}
package com.sahanswitch.participant.infrastructure;

import com.sahanswitch.participant.domain.Participant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ParticipantRepository extends JpaRepository<Participant, UUID> {

    boolean existsByCode(String code);
}
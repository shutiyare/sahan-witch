package com.sahanswitch.participant.api;

import com.sahanswitch.participant.domain.ParticipantStatus;
import com.sahanswitch.participant.domain.ParticipantType;

import java.time.Instant;
import java.util.UUID;

public record ParticipantResponse(
        UUID id,
        String code,
        String name,
        ParticipantType type,
        ParticipantStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}
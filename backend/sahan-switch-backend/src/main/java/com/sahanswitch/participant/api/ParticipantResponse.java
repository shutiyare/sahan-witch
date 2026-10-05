package com.sahanswitch.participant.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sahanswitch.participant.domain.ParticipantStatus;
import com.sahanswitch.participant.domain.ParticipantType;

import java.time.Instant;
import java.util.UUID;

/**
 * @param hasApiKey whether the participant can already authenticate with an API key
 * @param apiKey    the clear-text API key. Only present in the response that CREATES the
 *                  participant; afterwards only a hash is stored, so it can never be shown again
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ParticipantResponse(
        UUID id,
        String code,
        String name,
        ParticipantType type,
        ParticipantStatus status,
        Instant createdAt,
        Instant updatedAt,
        boolean hasApiKey,
        String apiKey
) {
}

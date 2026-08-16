package com.sahanswitch.participant.api;

import com.sahanswitch.participant.domain.ParticipantType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateParticipantRequest(

        @NotBlank(message = "Participant code is required")
        @Size(max = 50, message = "Participant code must not exceed 50 characters")
        String code,

        @NotBlank(message = "Participant name is required")
        @Size(max = 150, message = "Participant name must not exceed 150 characters")
        String name,

        @NotNull(message = "Participant type is required")
        ParticipantType type
) {
}
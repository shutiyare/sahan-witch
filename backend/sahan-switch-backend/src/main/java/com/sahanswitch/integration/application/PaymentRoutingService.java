package com.sahanswitch.integration.application;

import com.sahanswitch.common.exception.ResourceNotFoundException;
import com.sahanswitch.integration.domain.ParticipantIntegrationClient;
import com.sahanswitch.participant.domain.Participant;
import com.sahanswitch.participant.infrastructure.ParticipantRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PaymentRoutingService {

    private final ParticipantRepository participantRepository;

    private final List<ParticipantIntegrationClient> integrationClients;

    public PaymentRoutingService(
            ParticipantRepository participantRepository,
            List<ParticipantIntegrationClient> integrationClients
    ) {
        this.participantRepository = participantRepository;
        this.integrationClients = integrationClients;
    }

    public ParticipantIntegrationClient routeTo(String participantCode) {

        Participant participant =
                participantRepository
                        .findByCode(participantCode)
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Participant not found: "
                                                + participantCode
                                )
                        );

        if (!participant.isActive()) {

            throw new IllegalStateException(
                    "Participant is not active: "
                            + participantCode
            );
        }

        return integrationClients.stream()
                .filter(client -> client.supports(participant))
                .findFirst()
                .orElseThrow(() ->
                        new IllegalStateException(
                                "No integration client found for participant: "
                                        + participantCode
                        )
                );
    }
}
package com.sahanswitch.integration.infrastructure;

import com.sahanswitch.integration.domain.IntegrationResult;
import com.sahanswitch.integration.domain.IntegrationStatus;
import com.sahanswitch.integration.domain.ParticipantIntegrationClient;
import com.sahanswitch.payment.domain.Payment;
import com.sahanswitch.participant.domain.Participant;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class MockParticipantIntegrationClient
        implements ParticipantIntegrationClient {

    @Override
    public boolean supports(Participant participant) {
        return true;
    }

    @Override
    public IntegrationResult process(Payment payment, Participant participant) {

        return new IntegrationResult(
                IntegrationStatus.SUCCESS,
                UUID.randomUUID().toString(),
                "Payment accepted by participant: "
                        + participant.getCode()
        );
    }
}
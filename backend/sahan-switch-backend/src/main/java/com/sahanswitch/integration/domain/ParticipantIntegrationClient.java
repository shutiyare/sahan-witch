package com.sahanswitch.integration.domain;

import com.sahanswitch.payment.domain.Payment;
import com.sahanswitch.participant.domain.Participant;

public interface ParticipantIntegrationClient {

    boolean supports(Participant participant);

    IntegrationResult process(Payment payment, Participant participant);
}
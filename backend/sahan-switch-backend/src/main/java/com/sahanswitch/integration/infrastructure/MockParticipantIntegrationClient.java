package com.sahanswitch.integration.infrastructure;

import com.sahanswitch.integration.domain.IntegrationResult;
import com.sahanswitch.integration.domain.ParticipantIntegrationClient;
import com.sahanswitch.participant.domain.Participant;
import com.sahanswitch.payment.domain.Payment;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * <b>Mock</b> participant connector used until real bank/wallet adapters exist.
 *
 * <p>It supports every participant and normally accepts the payment. To exercise the failure
 * paths (and the retry / circuit-breaker logic of {@code PaymentRoutingService}) end to end
 * without a real counterpart, its answer can be steered by the <i>destination account</i>:
 * <ul>
 *   <li>{@code FAIL-...}    -> the participant rejects the payment (FAILED, not retried)</li>
 *   <li>{@code TIMEOUT-...} -> the participant never answers (TIMEOUT, retried 3 times)</li>
 *   <li>anything else       -> SUCCESS with a random external reference</li>
 * </ul>
 * Remove (or restrict to a profile) once a real client implements
 * {@link ParticipantIntegrationClient}.
 */
@Component
public class MockParticipantIntegrationClient
        implements ParticipantIntegrationClient {

    static final String FAIL_PREFIX = "FAIL-";
    static final String TIMEOUT_PREFIX = "TIMEOUT-";

    @Override
    public boolean supports(Participant participant) {
        return true;
    }

    @Override
    public IntegrationResult process(Payment payment, Participant participant) {

        String destinationAccount = payment.getDestinationAccount();

        if (destinationAccount.startsWith(FAIL_PREFIX)) {
            return IntegrationResult.failed(
                    "Rejected by participant " + participant.getCode() + ": account cannot receive funds"
            );
        }

        if (destinationAccount.startsWith(TIMEOUT_PREFIX)) {
            return IntegrationResult.timeout(
                    "Participant " + participant.getCode() + " did not answer in time"
            );
        }

        return IntegrationResult.success(
                UUID.randomUUID().toString(),
                "Payment accepted by participant: " + participant.getCode()
        );
    }
}

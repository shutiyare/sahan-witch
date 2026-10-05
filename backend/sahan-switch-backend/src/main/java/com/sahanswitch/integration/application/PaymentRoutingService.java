package com.sahanswitch.integration.application;

import com.sahanswitch.common.exception.ResourceNotFoundException;
import com.sahanswitch.integration.domain.IntegrationResult;
import com.sahanswitch.integration.domain.IntegrationStatus;
import com.sahanswitch.integration.domain.ParticipantIntegrationClient;
import com.sahanswitch.integration.domain.ParticipantUnavailableException;
import com.sahanswitch.participant.domain.Participant;
import com.sahanswitch.participant.infrastructure.ParticipantRepository;
import com.sahanswitch.payment.domain.Payment;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Chooses the connector for a destination participant and sends the payment through it.
 *
 * <p><b>Task 3.2</b> - this is the routing step of the payment pipeline.
 * <p><b>Task 5.3</b> - every call to a participant is protected by
 * <ol>
 *   <li>a <b>circuit breaker</b> per participant: after too many failures the participant is
 *       "cut off" for a while and payments fail fast instead of piling up behind timeouts;</li>
 *   <li>a <b>retry</b> with exponential backoff: up to 3 attempts for transient problems;</li>
 *   <li>a <b>fallback</b>: if the participant stays unreachable (or the circuit is open) the
 *       caller still gets an {@link IntegrationResult} of status TIMEOUT, never an exception, so
 *       the payment ends cleanly in FAILED with a clear reason.</li>
 * </ol>
 *
 * <p>Order matters: the retry wraps the circuit breaker, so <i>each</i> attempt is recorded by
 * the breaker and an open breaker stops the retries immediately.
 */
@Service
public class PaymentRoutingService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentRoutingService.class);

    private final ParticipantRepository participantRepository;

    private final List<ParticipantIntegrationClient> integrationClients;

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    private final Retry retry;

    public PaymentRoutingService(
            ParticipantRepository participantRepository,
            List<ParticipantIntegrationClient> integrationClients,
            CircuitBreakerRegistry circuitBreakerRegistry,
            Retry retry
    ) {
        this.participantRepository = participantRepository;
        this.integrationClients = integrationClients;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.retry = retry;
    }

    /**
     * Finds the integration client able to talk to the given participant.
     */
    public ParticipantIntegrationClient routeTo(String participantCode) {

        Participant participant = findActiveParticipant(participantCode);

        return findClient(participant)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "No integration client found for participant: "
                                        + participantCode
                        )
                );
    }

    /**
     * Sends the payment to the destination participant and returns the outcome.
     *
     * <p>Never throws because of a participant-side problem: the returned
     * {@link IntegrationResult} is SUCCESS, FAILED (participant said no) or TIMEOUT
     * (participant unreachable, retries exhausted, or circuit open).
     */
    public IntegrationResult routeTo(String participantCode, Payment payment) {

        Participant participant = findActiveParticipant(participantCode);

        Optional<ParticipantIntegrationClient> client = findClient(participant);

        if (client.isEmpty()) {
            return IntegrationResult.failed(
                    "No integration client found for participant: " + participantCode
            );
        }

        return callWithResilience(client.get(), payment, participant);
    }

    /**
     * Runs the participant call through circuit breaker + retry and converts every outcome
     * (including fault-tolerance fallbacks) into an {@link IntegrationResult}.
     */
    private IntegrationResult callWithResilience(
            ParticipantIntegrationClient client,
            Payment payment,
            Participant participant
    ) {
        String code = participant.getCode();

        // One breaker per participant code, created on first use with the shared configuration.
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(code);

        AtomicInteger attempts = new AtomicInteger();

        // The raw call. Technical problems are turned into exceptions so that the circuit
        // breaker records them as failures and the retry repeats them.
        Supplier<IntegrationResult> rawCall = () -> {
            int attempt = attempts.incrementAndGet();
            logger.debug("Calling participant {} (attempt {})", code, attempt);

            IntegrationResult result;
            try {
                result = client.process(payment, participant);
            } catch (ParticipantUnavailableException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                // Connection refused, client bug, ... : treat as "participant unavailable".
                throw new ParticipantUnavailableException(
                        "Integration error: " + exception.getMessage(), exception);
            }

            if (result == null || result.status() == null) {
                throw new ParticipantUnavailableException("Participant returned an empty response");
            }

            if (result.status() == IntegrationStatus.TIMEOUT) {
                throw new ParticipantUnavailableException(
                        result.failureReason() != null ? result.failureReason() : "Participant timed out");
            }

            // SUCCESS or FAILED: the participant answered. A business rejection is a valid answer,
            // it must neither be retried nor count against the circuit breaker.
            return result;
        };

        // retry( circuitBreaker( rawCall ) )
        Supplier<IntegrationResult> guardedCall =
                Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(circuitBreaker, rawCall));

        try {
            return guardedCall.get();

        } catch (CallNotPermittedException exception) {
            // Fallback 1: circuit is open, the participant was not even called.
            logger.warn("Circuit breaker is open for participant {}, failing fast", code);
            return IntegrationResult.timeout(
                    "Participant " + code + " is temporarily unavailable (circuit breaker open)"
            );

        } catch (RuntimeException exception) {
            // Fallback 2: retries exhausted.
            logger.warn("Participant {} unreachable after {} attempt(s): {}",
                    code, attempts.get(), exception.getMessage());
            return IntegrationResult.timeout(
                    "Participant " + code + " unavailable after " + attempts.get()
                            + " attempt(s): " + exception.getMessage()
            );
        }
    }

    private Participant findActiveParticipant(String participantCode) {

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

        return participant;
    }

    private Optional<ParticipantIntegrationClient> findClient(Participant participant) {

        return integrationClients.stream()
                .filter(client -> client.supports(participant))
                .findFirst();
    }
}

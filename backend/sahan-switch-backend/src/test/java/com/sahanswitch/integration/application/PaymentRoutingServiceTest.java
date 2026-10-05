package com.sahanswitch.integration.application;

import com.sahanswitch.common.exception.ResourceNotFoundException;
import com.sahanswitch.integration.domain.IntegrationResult;
import com.sahanswitch.integration.domain.IntegrationStatus;
import com.sahanswitch.integration.domain.ParticipantIntegrationClient;
import com.sahanswitch.integration.infrastructure.ResilienceConfig;
import com.sahanswitch.integration.infrastructure.ResilienceProperties;
import com.sahanswitch.participant.domain.Participant;
import com.sahanswitch.participant.infrastructure.ParticipantRepository;
import com.sahanswitch.payment.domain.Payment;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 6.1 / 5.3: retry (3 attempts), circuit breaker and fallback of
 * {@link PaymentRoutingService}. Uses real Resilience4j objects built by
 * {@link ResilienceConfig}, with a 1 ms backoff so the tests stay fast.
 */
class PaymentRoutingServiceTest {

    private static final String CODE = "DEST";

    private ParticipantRepository participantRepository;
    private Participant participant;
    private Payment payment;
    private CircuitBreakerRegistry circuitBreakerRegistry;
    private Retry retry;

    @BeforeEach
    void setUp() {
        participantRepository = mock(ParticipantRepository.class);
        participant = mock(Participant.class);
        payment = mock(Payment.class);

        when(participant.getCode()).thenReturn(CODE);
        when(participant.isActive()).thenReturn(true);
        when(participantRepository.findByCode(CODE)).thenReturn(Optional.of(participant));

        ResilienceProperties properties = new ResilienceProperties(
                new ResilienceProperties.Retry(3, Duration.ofMillis(1), 2.0),
                // window of 4, needs 3 calls, opens at >= 50 % failures, stays open long enough for the test
                new ResilienceProperties.CircuitBreaker(4, 3, 50f, Duration.ofSeconds(30), 2)
        );

        ResilienceConfig config = new ResilienceConfig();
        retry = config.participantRetry(properties);
        circuitBreakerRegistry = config.participantCircuitBreakerRegistry(properties);
    }

    private PaymentRoutingService serviceWith(ParticipantIntegrationClient client) {
        return new PaymentRoutingService(participantRepository, List.of(client), circuitBreakerRegistry, retry);
    }

    /** A client that counts its calls and answers with whatever the supplier returns. */
    private static class CountingClient implements ParticipantIntegrationClient {

        final AtomicInteger calls = new AtomicInteger();
        private final java.util.function.IntFunction<IntegrationResult> answer;

        CountingClient(java.util.function.IntFunction<IntegrationResult> answer) {
            this.answer = answer;
        }

        @Override
        public boolean supports(Participant participant) {
            return true;
        }

        @Override
        public IntegrationResult process(Payment payment, Participant participant) {
            return answer.apply(calls.incrementAndGet());
        }
    }

    // ================================================================ no retry needed

    @Test
    void successIsReturnedAfterOneCall() {
        CountingClient client = new CountingClient(n -> IntegrationResult.success("EXT-1", "ok"));

        IntegrationResult result = serviceWith(client).routeTo(CODE, payment);

        assertEquals(IntegrationStatus.SUCCESS, result.status());
        assertEquals("EXT-1", result.externalReference());
        assertEquals(1, client.calls.get());
    }

    @Test
    void businessRejectionIsNotRetriedAndDoesNotCountAgainstTheCircuit() {
        CountingClient client = new CountingClient(n -> IntegrationResult.failed("Insufficient funds"));
        PaymentRoutingService service = serviceWith(client);

        // Many rejections in a row: the participant keeps answering, so the circuit must stay closed.
        for (int i = 0; i < 10; i++) {
            IntegrationResult result = service.routeTo(CODE, payment);
            assertEquals(IntegrationStatus.FAILED, result.status());
            assertEquals("Insufficient funds", result.failureReason());
        }

        assertEquals(10, client.calls.get(), "exactly one call per payment, no retries");
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreakerRegistry.circuitBreaker(CODE).getState());
    }

    // ================================================================ retry

    @Test
    void timeoutIsRetriedUpToThreeAttemptsThenFallsBack() {
        CountingClient client = new CountingClient(n -> IntegrationResult.timeout("no answer"));

        IntegrationResult result = serviceWith(client).routeTo(CODE, payment);

        assertEquals(3, client.calls.get());
        assertEquals(IntegrationStatus.TIMEOUT, result.status());
        assertTrue(result.failureReason().contains("unavailable after 3 attempt(s)"), result.failureReason());
        assertTrue(result.failureReason().contains("no answer"), result.failureReason());
    }

    @Test
    void paymentSucceedsWhenParticipantRecoversDuringRetries() {
        CountingClient client = new CountingClient(n ->
                n < 3 ? IntegrationResult.timeout("slow") : IntegrationResult.success("EXT-OK", "finally"));

        IntegrationResult result = serviceWith(client).routeTo(CODE, payment);

        assertEquals(IntegrationStatus.SUCCESS, result.status());
        assertEquals("EXT-OK", result.externalReference());
        assertEquals(3, client.calls.get());
    }

    @Test
    void clientExceptionsAreTreatedAsTransientAndRetried() {
        AtomicInteger calls = new AtomicInteger();
        ParticipantIntegrationClient client = new ParticipantIntegrationClient() {
            @Override
            public boolean supports(Participant participant) {
                return true;
            }

            @Override
            public IntegrationResult process(Payment payment, Participant participant) {
                calls.incrementAndGet();
                throw new IllegalStateException("connection refused");
            }
        };

        IntegrationResult result = serviceWith(client).routeTo(CODE, payment);

        assertEquals(3, calls.get());
        assertEquals(IntegrationStatus.TIMEOUT, result.status());
        assertTrue(result.failureReason().contains("connection refused"), result.failureReason());
    }

    @Test
    void emptyResponseIsTreatedAsUnavailable() {
        CountingClient client = new CountingClient(n -> null);

        IntegrationResult result = serviceWith(client).routeTo(CODE, payment);

        assertEquals(3, client.calls.get());
        assertEquals(IntegrationStatus.TIMEOUT, result.status());
        assertTrue(result.failureReason().contains("empty response"), result.failureReason());
    }

    // ================================================================ circuit breaker

    @Test
    void circuitOpensAfterRepeatedFailuresAndThenFailsFastWithoutCallingTheParticipant() {
        CountingClient client = new CountingClient(n -> IntegrationResult.timeout("down"));
        PaymentRoutingService service = serviceWith(client);

        // First payment: 3 failed attempts -> 3 recorded calls (min 3, 100 % failures) -> circuit opens.
        service.routeTo(CODE, payment);
        assertEquals(3, client.calls.get());
        assertEquals(CircuitBreaker.State.OPEN, circuitBreakerRegistry.circuitBreaker(CODE).getState());

        // Second payment: rejected immediately by the open circuit, the client is not called again.
        IntegrationResult second = service.routeTo(CODE, payment);

        assertEquals(3, client.calls.get(), "an open circuit must not reach the participant");
        assertEquals(IntegrationStatus.TIMEOUT, second.status());
        assertTrue(second.failureReason().contains("circuit breaker open"), second.failureReason());
    }

    @Test
    void circuitsAreIndependentPerParticipant() {
        CountingClient failing = new CountingClient(n -> IntegrationResult.timeout("down"));
        serviceWith(failing).routeTo(CODE, payment);
        assertEquals(CircuitBreaker.State.OPEN, circuitBreakerRegistry.circuitBreaker(CODE).getState());

        // Another participant with a healthy connector is unaffected.
        Participant other = mock(Participant.class);
        when(other.getCode()).thenReturn("OTHER");
        when(other.isActive()).thenReturn(true);
        when(participantRepository.findByCode("OTHER")).thenReturn(Optional.of(other));

        CountingClient healthy = new CountingClient(n -> IntegrationResult.success("EXT-2", "ok"));
        IntegrationResult result = serviceWith(healthy).routeTo("OTHER", payment);

        assertEquals(IntegrationStatus.SUCCESS, result.status());
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreakerRegistry.circuitBreaker("OTHER").getState());
    }

    // ================================================================ participant / client resolution

    @Test
    void missingClientIsReportedAsFailedResult() {
        ParticipantIntegrationClient noOne = new ParticipantIntegrationClient() {
            @Override
            public boolean supports(Participant participant) {
                return false;
            }

            @Override
            public IntegrationResult process(Payment payment, Participant participant) {
                throw new AssertionError("must not be called");
            }
        };

        IntegrationResult result = serviceWith(noOne).routeTo(CODE, payment);

        assertEquals(IntegrationStatus.FAILED, result.status());
        assertNotNull(result.failureReason());
        assertTrue(result.failureReason().contains("No integration client"));
    }

    @Test
    void unknownOrInactiveParticipantsAreRejected() {
        PaymentRoutingService service = serviceWith(new CountingClient(n -> IntegrationResult.success("x", "x")));

        when(participantRepository.findByCode("GHOST")).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.routeTo("GHOST", payment));

        when(participant.isActive()).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> service.routeTo(CODE, payment));
    }

    @Test
    void routeToCodeAloneReturnsTheMatchingClient() {
        CountingClient client = new CountingClient(n -> IntegrationResult.success("x", "x"));

        assertEquals(client, serviceWith(client).routeTo(CODE));
    }
}

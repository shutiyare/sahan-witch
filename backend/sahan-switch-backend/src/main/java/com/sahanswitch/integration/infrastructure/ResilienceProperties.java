package com.sahanswitch.integration.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Tunable fault-tolerance settings for calls to participants.
 *
 * <p><b>Task 5.3.</b> Everything has a safe default, so nothing has to be configured; override
 * under {@code sahanswitch.resilience.*} in {@code application*.yml} (or with environment
 * variables). Example: {@code sahanswitch.resilience.retry.initial-backoff=0ms} switches the
 * waiting off in tests.
 *
 * @param retry          retry policy for one call to a participant
 * @param circuitBreaker per-participant circuit breaker
 */
@ConfigurationProperties(prefix = "sahanswitch.resilience")
public record ResilienceProperties(
        @DefaultValue Retry retry,
        @DefaultValue CircuitBreaker circuitBreaker
) {

    /**
     * @param maxAttempts       total attempts including the first one (3 = first try + 2 retries)
     * @param initialBackoff    wait before the first retry
     * @param backoffMultiplier each further wait is the previous one times this (200ms, 400ms, ...)
     */
    public record Retry(
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("200ms") Duration initialBackoff,
            @DefaultValue("2.0") double backoffMultiplier
    ) {
    }

    /**
     * @param slidingWindowSize         number of most recent calls the failure rate is computed over
     * @param minimumNumberOfCalls      the breaker never opens before this many calls were recorded
     * @param failureRateThreshold      percentage of failed calls (0-100) that opens the circuit
     * @param waitDurationInOpenState   how long calls are rejected before probing the participant again
     * @param permittedCallsInHalfOpen  probe calls allowed while half-open
     */
    public record CircuitBreaker(
            @DefaultValue("10") int slidingWindowSize,
            @DefaultValue("5") int minimumNumberOfCalls,
            @DefaultValue("50") float failureRateThreshold,
            @DefaultValue("30s") Duration waitDurationInOpenState,
            @DefaultValue("3") int permittedCallsInHalfOpen
    ) {
    }
}

package com.sahanswitch.integration.domain;

/**
 * A technical (not business) problem talking to a participant: it timed out, answered nothing
 * or the connection failed.
 *
 * <p><b>Task 5.3.</b> Thrown inside the guarded call so Resilience4j counts it as a failure
 * (circuit breaker) and retries it. A business rejection ({@link IntegrationStatus#FAILED}) is
 * <i>not</i> modelled as an exception: the participant answered, so retrying would not help and
 * the circuit must stay closed.
 */
public class ParticipantUnavailableException extends RuntimeException {

    public ParticipantUnavailableException(String message) {
        super(message);
    }

    public ParticipantUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

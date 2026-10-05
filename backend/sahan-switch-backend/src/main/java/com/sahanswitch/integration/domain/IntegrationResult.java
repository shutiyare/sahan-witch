package com.sahanswitch.integration.domain;

/**
 * Outcome of sending a payment to a destination participant.
 *
 * <p><b>Task 1.2 (clean-up of {@code IntegrationResult}).</b> This is a plain record with
 * explicit, typed fields. The old version declared a generic type parameter that was
 * named {@code IntegrationStatus}, which shadowed the real enum and forced raw-type usage
 * everywhere. Use the static factories below instead of the canonical constructor so
 * that every result is built consistently.
 *
 * @param status            what happened (SUCCESS, FAILED or TIMEOUT)
 * @param externalReference the participant's own reference for the transaction (SUCCESS only)
 * @param failureReason     machine/human readable reason why the participant did not accept the
 *                          payment (FAILED and TIMEOUT only). Stored on the payment.
 * @param message           free-text diagnostic message, useful for logs
 */
public record IntegrationResult(

        IntegrationStatus status,

        String externalReference,

        String failureReason,

        String message

) {

    /** The participant accepted and booked the payment. */
    public static IntegrationResult success(String externalReference, String message) {
        return new IntegrationResult(IntegrationStatus.SUCCESS, externalReference, null, message);
    }

    /** The participant explicitly rejected the payment (business failure, not retryable). */
    public static IntegrationResult failed(String failureReason) {
        return new IntegrationResult(IntegrationStatus.FAILED, null, failureReason, failureReason);
    }

    /** The participant did not answer in time or is unreachable (technical failure, retryable). */
    public static IntegrationResult timeout(String failureReason) {
        return new IntegrationResult(IntegrationStatus.TIMEOUT, null, failureReason, failureReason);
    }
}

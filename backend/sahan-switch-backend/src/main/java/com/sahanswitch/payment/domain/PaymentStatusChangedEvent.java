package com.sahanswitch.payment.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Spring domain event published every time a payment changes status.
 *
 * <p><b>Task 5.2.</b> {@code PaymentService} publishes this event for every transition
 * (creation, PROCESSING, COMPLETED, FAILED). Listeners - currently the audit service - react
 * to it without {@code PaymentService} knowing about them, which keeps payment processing
 * decoupled from auditing (and from anything we add later, e.g. webhooks or metrics).
 *
 * @param paymentId      the payment that changed
 * @param previousStatus status before the change; {@code null} when the payment was just created
 * @param newStatus      status after the change
 * @param reason         human readable explanation (rejection reason, "completed by ...", ...)
 * @param correlationId  id of the HTTP request that triggered the change (may be {@code null}
 *                       when there is no request, e.g. in a background job)
 * @param occurredAt     when the transition happened
 */
public record PaymentStatusChangedEvent(
        UUID paymentId,
        PaymentStatus previousStatus,
        PaymentStatus newStatus,
        String reason,
        String correlationId,
        Instant occurredAt
) {
}

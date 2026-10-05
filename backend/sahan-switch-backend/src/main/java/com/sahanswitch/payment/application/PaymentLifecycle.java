package com.sahanswitch.payment.application;

import com.sahanswitch.common.web.CorrelationIdFilter;
import com.sahanswitch.integration.domain.IntegrationResult;
import com.sahanswitch.payment.domain.Payment;
import com.sahanswitch.payment.domain.PaymentStatus;
import com.sahanswitch.payment.domain.PaymentStatusChangedEvent;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;

/**
 * The one place where a payment changes status.
 *
 * <p>Extracted from {@code PaymentService} (Task 2) because two code paths now move payments
 * through the state machine: the synchronous request path and the asynchronous routing consumer.
 * Sharing this component guarantees both apply the same rules and publish the same events
 * (which feed the audit trail and the outbox).
 */
@Component
public class PaymentLifecycle {

    private final ApplicationEventPublisher eventPublisher;

    public PaymentLifecycle(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * Applies a state-machine transition and publishes the matching event.
     *
     * <p>Every status change goes through here, so none can forget the event. The previous
     * status is captured <em>before</em> the transition runs. If the transition is illegal the
     * entity throws and no event is published.
     */
    public void transition(Payment payment, Runnable transition, String reason) {
        PaymentStatus previous = payment.getStatus();
        transition.run();
        publishStatusChange(payment, previous, reason);
    }

    /**
     * Publishes {@link PaymentStatusChangedEvent}.
     *
     * <p>The audit and outbox listeners run {@code BEFORE_COMMIT}, so an event published outside a
     * transaction would be dropped silently - and an audit entry would be lost without anyone
     * noticing. We refuse to do that and fail loudly instead.
     */
    public void publishStatusChange(Payment payment, PaymentStatus previous, String reason) {

        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Payment status changes must happen inside a transaction so they can be audited"
            );
        }

        eventPublisher.publishEvent(new PaymentStatusChangedEvent(
                payment.getId(),
                previous,
                payment.getStatus(),
                reason,
                MDC.get(CorrelationIdFilter.MDC_KEY),
                Instant.now()
        ));
    }

    /** Translates what the participant answered into the final payment state. */
    public void applyIntegrationResult(Payment payment, String destinationCode, IntegrationResult result) {

        switch (result.status()) {
            case SUCCESS -> transition(
                    payment,
                    () -> payment.markCompleted(result.externalReference()),
                    "Completed by participant " + destinationCode
                            + ", externalReference=" + result.externalReference()
            );

            case TIMEOUT -> {
                String reason = "Participant timeout: " + describeFailure(result);
                transition(payment, () -> payment.markFailed(reason), reason);
            }

            case FAILED -> {
                String reason = describeFailure(result);
                transition(payment, () -> payment.markFailed(reason), reason);
            }
        }
    }

    private String describeFailure(IntegrationResult result) {
        if (result.failureReason() != null && !result.failureReason().isBlank()) {
            return result.failureReason();
        }
        if (result.message() != null && !result.message().isBlank()) {
            return result.message();
        }
        return "Unknown failure";
    }
}

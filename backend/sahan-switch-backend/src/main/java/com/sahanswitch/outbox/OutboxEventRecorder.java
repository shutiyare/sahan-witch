package com.sahanswitch.outbox;

import com.sahanswitch.payment.domain.PaymentStatusChangedEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Task 2: copies every {@link PaymentStatusChangedEvent} into the outbox.
 *
 * <p>Runs {@code BEFORE_COMMIT}, exactly like the audit listener, so the outbox row is part of
 * the same transaction as the status change: it exists if and only if the change was committed.
 * Downstream systems can subscribe to the {@code payment-status-events} queue to follow
 * payments in real time without polling the API.
 */
@Component
@ConditionalOnProperty(prefix = "sahanswitch.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxEventRecorder {

    private final OutboxService outboxService;

    public OutboxEventRecorder(OutboxService outboxService) {
        this.outboxService = outboxService;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onPaymentStatusChanged(PaymentStatusChangedEvent event) {

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentId", event.paymentId());
        payload.put("previousStatus", event.previousStatus());
        payload.put("newStatus", event.newStatus());
        payload.put("reason", event.reason());
        payload.put("correlationId", event.correlationId());
        payload.put("occurredAt", event.occurredAt());

        outboxService.append(
                OutboxEventTypes.AGGREGATE_PAYMENT,
                event.paymentId(),
                OutboxEventTypes.PAYMENT_STATUS_CHANGED,
                payload
        );
    }
}

package com.sahanswitch.audit.application;

import com.sahanswitch.audit.api.PaymentAuditLogResponse;
import com.sahanswitch.audit.domain.PaymentAuditLog;
import com.sahanswitch.audit.infrastructure.PaymentAuditLogRepository;
import com.sahanswitch.common.exception.ResourceNotFoundException;
import com.sahanswitch.payment.domain.PaymentStatusChangedEvent;
import com.sahanswitch.payment.infrastructure.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.UUID;

/**
 * Writes the payment audit trail.
 *
 * <p><b>Task 5.2.</b> Listens to {@link PaymentStatusChangedEvent} and stores one immutable
 * {@link PaymentAuditLog} per event.
 *
 * <p><b>Why {@code BEFORE_COMMIT} and not {@code @Async}/{@code AFTER_COMMIT}:</b> the audit
 * row is written inside the <em>same</em> database transaction as the status change. They
 * commit together or roll back together, so the trail can neither miss a committed change
 * (crash right after commit) nor describe a change that never happened. For a financial
 * audit log that guarantee is worth more than the few milliseconds saved by writing it
 * asynchronously.
 */
@Service
public class PaymentAuditService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentAuditService.class);

    private final PaymentAuditLogRepository auditLogRepository;
    private final PaymentRepository paymentRepository;

    public PaymentAuditService(
            PaymentAuditLogRepository auditLogRepository,
            PaymentRepository paymentRepository
    ) {
        this.auditLogRepository = auditLogRepository;
        this.paymentRepository = paymentRepository;
    }

    /**
     * Runs just before the publisher's transaction commits and joins that transaction.
     * If this insert fails, the exception aborts the commit and the payment change is rolled
     * back as well: no change without an audit entry.
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onPaymentStatusChanged(PaymentStatusChangedEvent event) {

        auditLogRepository.save(PaymentAuditLog.from(event));

        logger.info("AUDIT payment={} {} -> {} reason='{}'",
                event.paymentId(), event.previousStatus(), event.newStatus(), event.reason());
    }

    /** Full audit history of a payment, oldest first. */
    @Transactional(readOnly = true)
    public List<PaymentAuditLogResponse> getHistory(UUID paymentId) {

        if (!paymentRepository.existsById(paymentId)) {
            throw new ResourceNotFoundException("Payment not found: " + paymentId);
        }

        return auditLogRepository
                .findByPaymentIdOrderByCreatedAtAsc(paymentId)
                .stream()
                .map(PaymentAuditLogResponse::from)
                .toList();
    }
}

package com.sahanswitch.audit.api;

import com.sahanswitch.audit.domain.PaymentAuditLog;
import com.sahanswitch.payment.domain.PaymentStatus;

import java.time.Instant;
import java.util.UUID;

/** API view of one audit entry. */
public record PaymentAuditLogResponse(
        UUID id,
        UUID paymentId,
        PaymentStatus previousStatus,
        PaymentStatus newStatus,
        String reason,
        String correlationId,
        Instant createdAt
) {

    public static PaymentAuditLogResponse from(PaymentAuditLog log) {
        return new PaymentAuditLogResponse(
                log.getId(),
                log.getPaymentId(),
                log.getPreviousStatus(),
                log.getNewStatus(),
                log.getReason(),
                log.getCorrelationId(),
                log.getCreatedAt()
        );
    }
}

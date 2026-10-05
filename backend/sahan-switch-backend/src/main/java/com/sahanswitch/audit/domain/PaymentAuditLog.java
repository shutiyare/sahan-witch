package com.sahanswitch.audit.domain;

import com.sahanswitch.payment.domain.PaymentStatus;
import com.sahanswitch.payment.domain.PaymentStatusChangedEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/**
 * One immutable row of the payment audit trail (table {@code payment_audit_logs}, V7).
 *
 * <p><b>Task 5.1 / 5.2.</b> Immutability is enforced twice:
 * <ul>
 *   <li>in Java: {@link Immutable} makes Hibernate refuse updates, there are no setters and
 *       the columns are {@code updatable = false};</li>
 *   <li>in PostgreSQL: a trigger rejects {@code UPDATE} and {@code DELETE} (see V7).</li>
 * </ul>
 * The payment is referenced by plain id (not a JPA association) on purpose: the audit trail
 * must never load or modify the payment itself.
 */
@Entity
@Immutable
@Table(name = "payment_audit_logs")
public class PaymentAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    /** {@code null} for the first entry of a payment. */
    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", updatable = false, length = 30)
    private PaymentStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, updatable = false, length = 30)
    private PaymentStatus newStatus;

    @Column(name = "reason", updatable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "correlation_id", updatable = false, length = 100)
    private String correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PaymentAuditLog() {
        // Required by JPA
    }

    /** Builds the audit row that records the given status change. */
    public static PaymentAuditLog from(PaymentStatusChangedEvent event) {
        PaymentAuditLog log = new PaymentAuditLog();
        log.paymentId = event.paymentId();
        log.previousStatus = event.previousStatus();
        log.newStatus = event.newStatus();
        log.reason = event.reason();
        log.correlationId = event.correlationId();
        log.createdAt = event.occurredAt();
        return log;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public PaymentStatus getPreviousStatus() {
        return previousStatus;
    }

    public PaymentStatus getNewStatus() {
        return newStatus;
    }

    public String getReason() {
        return reason;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

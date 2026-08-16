package com.sahanswitch.payment.domain;

import com.sahanswitch.participant.domain.Participant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "payments")
@EntityListeners(AuditingEntityListener.class)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "payment_reference",
            nullable = false,
            unique = true,
            length = 50)
    private String paymentReference;

    @Column(name = "idempotency_key",
            nullable = false,
            length = 100)
    private String idempotencyKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "participant_id", nullable = false)
    private Participant participant;

    @Column(name = "source_account",
            nullable = false,
            length = 100)
    private String sourceAccount;

    @Column(name = "destination_account",
            nullable = false,
            length = 100)
    private String destinationAccount;

    @Column(nullable = false,
            precision = 19,
            scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentStatus status;

    @CreatedDate
    @Column(name = "created_at",
            nullable = false,
            updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at",
            nullable = false)
    private Instant updatedAt;

    protected Payment() {
    }

    public Payment(
            String paymentReference,
            String idempotencyKey,
            Participant participant,
            String sourceAccount,
            String destinationAccount,
            BigDecimal amount,
            String currency
    ) {
        this.paymentReference = paymentReference;
        this.idempotencyKey = idempotencyKey;
        this.participant = participant;
        this.sourceAccount = sourceAccount;
        this.destinationAccount = destinationAccount;
        this.amount = amount;
        this.currency = currency;
        this.status = PaymentStatus.ACCEPTED;
    }

    public void startProcessing() {
        if (this.status != PaymentStatus.ACCEPTED) {
            throw new IllegalStateException(
                    "Only ACCEPTED payments can start processing"
            );
        }

        this.status = PaymentStatus.PROCESSING;
    }

    public void complete() {
        if (this.status != PaymentStatus.PROCESSING) {
            throw new IllegalStateException(
                    "Only PROCESSING payments can be completed"
            );
        }

        this.status = PaymentStatus.COMPLETED;
    }

    public void fail() {
        if (this.status != PaymentStatus.PROCESSING) {
            throw new IllegalStateException(
                    "Only PROCESSING payments can fail"
            );
        }

        this.status = PaymentStatus.FAILED;
    }

    public UUID getId() {
        return id;
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Participant getParticipant() {
        return participant;
    }

    public String getSourceAccount() {
        return sourceAccount;
    }

    public String getDestinationAccount() {
        return destinationAccount;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
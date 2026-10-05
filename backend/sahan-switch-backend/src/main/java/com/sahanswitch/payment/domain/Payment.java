package com.sahanswitch.payment.domain;

import com.sahanswitch.common.exception.InvalidPaymentStateException;
import com.sahanswitch.participant.domain.Participant;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "payments")
@EntityListeners(AuditingEntityListener.class)
public class Payment {

    /**
     * Upper bound for a stored failure reason. The column is TEXT (V8), so this is not a
     * database limit; it only stops a misbehaving participant from filling the table with
     * megabytes of error text.
     */
    private static final int FAILURE_REASON_MAX_LENGTH = 4000;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "payment_reference",
            nullable = false,
            unique = true,
            length = 50)
    private String paymentReference;

    @Column(name = "idempotency_key",
            nullable = false,
            length = 100)
    private String idempotencyKey;

    /** The participant that initiated the payment (column kept as participant_id). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "participant_id", nullable = false)
    private Participant senderParticipant;

    /** The participant that receives the payment. Null only for legacy rows created before V6. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "destination_participant_id")
    private Participant destinationParticipant;

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

    @Column(name = "external_reference", length = 100)
    private String externalReference;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    // ---- ISO 20022 identifiers (Task 4, columns added in V8). All optional for plain JSON payments.

    /** ISO "EndToEndId" (Max35Text): the reference the original sender gave the payment. */
    @Column(name = "end_to_end_id", length = 35)
    private String endToEndId;

    /** ISO "UETR": globally unique id of this transaction (UUID v4), constant along the whole chain. */
    @Column(name = "uetr")
    private UUID uetr;

    /** Debtor (payer) name as it appears in the ISO message. */
    @Column(name = "debtor_name", length = 140)
    private String debtorName;

    /** Creditor (payee) name as it appears in the ISO message. */
    @Column(name = "creditor_name", length = 140)
    private String creditorName;

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
        // Required by JPA
    }

    /**
     * Creates a plain payment (no ISO identifiers supplied): a UETR is generated and the
     * EndToEndId defaults to the payment reference.
     */
    public static Payment create(
            Participant sender,
            Participant destination,
            String sourceAccount,
            String destinationAccount,
            BigDecimal amount,
            String currency,
            String idempotencyKey
    ) {
        return create(sender, destination, sourceAccount, destinationAccount,
                amount, currency, idempotencyKey, null, null, null, null);
    }

    /**
     * Creates a new payment in status {@code ACCEPTED}.
     *
     * @param endToEndId   ISO EndToEndId, or {@code null} to default to the payment reference
     * @param uetr         ISO UETR, or {@code null} to generate a random UUID v4
     * @param debtorName   optional payer name
     * @param creditorName optional payee name
     */
    public static Payment create(
            Participant sender,
            Participant destination,
            String sourceAccount,
            String destinationAccount,
            BigDecimal amount,
            String currency,
            String idempotencyKey,
            String endToEndId,
            UUID uetr,
            String debtorName,
            String creditorName
    ) {
        Payment payment = new Payment();
        payment.paymentReference = generatePaymentReference();
        payment.idempotencyKey = idempotencyKey;
        payment.senderParticipant = sender;
        payment.destinationParticipant = destination;
        payment.sourceAccount = sourceAccount;
        payment.destinationAccount = destinationAccount;
        payment.amount = amount;
        payment.currency = currency.toUpperCase(Locale.ROOT);
        payment.status = PaymentStatus.ACCEPTED;
        payment.endToEndId = (endToEndId == null || endToEndId.isBlank())
                ? payment.paymentReference
                : endToEndId;
        payment.uetr = uetr != null ? uetr : UUID.randomUUID();
        payment.debtorName = debtorName;
        payment.creditorName = creditorName;
        return payment;
    }

    private static String generatePaymentReference() {
        String randomPart = UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 12)
                .toUpperCase(Locale.ROOT);

        return "SHN-" + randomPart;
    }

    public void markProcessing() {
        if (this.status != PaymentStatus.ACCEPTED) {
            throw new InvalidPaymentStateException(
                    "Only ACCEPTED payments can start processing"
            );
        }

        this.status = PaymentStatus.PROCESSING;
    }

    public void markCompleted(String externalReference) {
        if (this.status != PaymentStatus.PROCESSING) {
            throw new InvalidPaymentStateException(
                    "Only PROCESSING payments can be completed"
            );
        }

        this.status = PaymentStatus.COMPLETED;
        this.externalReference = externalReference;
    }

    public void markFailed(String reason) {
        if (this.status != PaymentStatus.ACCEPTED && this.status != PaymentStatus.PROCESSING) {
            throw new InvalidPaymentStateException(
                    "Only ACCEPTED or PROCESSING payments can fail"
            );
        }

        this.status = PaymentStatus.FAILED;
        this.failureReason = truncate(reason);
    }

    private static String truncate(String reason) {
        if (reason == null || reason.isBlank()) {
            return "Unknown failure";
        }
        return reason.length() <= FAILURE_REASON_MAX_LENGTH
                ? reason
                : reason.substring(0, FAILURE_REASON_MAX_LENGTH);
    }

    public UUID getId() {
        return id;
    }

    public Long getVersion() {
        return version;
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Participant getSenderParticipant() {
        return senderParticipant;
    }

    public Participant getDestinationParticipant() {
        return destinationParticipant;
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

    public String getExternalReference() {
        return externalReference;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getEndToEndId() {
        return endToEndId;
    }

    public UUID getUetr() {
        return uetr;
    }

    public String getDebtorName() {
        return debtorName;
    }

    public String getCreditorName() {
        return creditorName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

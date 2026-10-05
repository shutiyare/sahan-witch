package com.sahanswitch.payment.api;

import com.sahanswitch.payment.domain.Payment;
import com.sahanswitch.payment.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * API view of a {@link Payment}.
 *
 * <p><b>Task 2.2.</b> Exposes both participants, both accounts and - once the payment has
 * been routed - the participant's {@code externalReference} (on success) or the
 * {@code failureReason} (on failure). The ISO identifiers (Task 4) let clients correlate
 * the payment with the pacs.008 / pacs.002 messages.
 */
public record PaymentResponse(
        UUID id,
        String paymentReference,
        UUID senderParticipantId,
        UUID destinationParticipantId,
        String sourceAccount,
        String destinationAccount,
        BigDecimal amount,
        String currency,
        PaymentStatus status,
        String externalReference,
        String failureReason,
        String endToEndId,
        UUID uetr,
        String debtorName,
        String creditorName,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * Maps an entity to its response. Must be called while the persistence session is still
     * open, because the participants are lazily loaded.
     */
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getPaymentReference(),
                payment.getSenderParticipant().getId(),
                payment.getDestinationParticipant() == null
                        ? null
                        : payment.getDestinationParticipant().getId(),
                payment.getSourceAccount(),
                payment.getDestinationAccount(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getExternalReference(),
                payment.getFailureReason(),
                payment.getEndToEndId(),
                payment.getUetr(),
                payment.getDebtorName(),
                payment.getCreditorName(),
                payment.getCreatedAt(),
                payment.getUpdatedAt()
        );
    }
}

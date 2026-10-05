package com.sahanswitch.payment.application;

import com.sahanswitch.payment.domain.PaymentStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Task 3: the optional filters of {@code GET /api/v1/payments/search}. A null field means
 * "do not filter on this".
 *
 * @param from      inclusive lower bound of {@code createdAt}
 * @param to        inclusive upper bound of {@code createdAt}
 * @param reference case-insensitive "contains" match on the payment reference (SHN-...)
 */
public record PaymentSearchCriteria(
        UUID senderParticipantId,
        UUID destinationParticipantId,
        PaymentStatus status,
        String currency,
        Instant from,
        Instant to,
        String reference
) {
}

package com.sahanswitch.payment.infrastructure;

import com.sahanswitch.payment.application.PaymentSearchCriteria;
import com.sahanswitch.payment.domain.Payment;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Task 3: builds the dynamic WHERE clause of the payment search with Spring Data JPA
 * Specifications. Each filter that is present adds one predicate; all predicates are ANDed.
 * Every value reaches the database as a bind parameter, never concatenated into SQL.
 */
public final class PaymentSpecifications {

    private PaymentSpecifications() {
    }

    /**
     * @param participantScope when not null, only payments this participant sent or received are
     *                         visible (used for participant identities; administrators pass null)
     */
    public static Specification<Payment> from(PaymentSearchCriteria criteria, UUID participantScope) {

        return (root, query, builder) -> {

            List<Predicate> predicates = new ArrayList<>();

            if (participantScope != null) {
                predicates.add(builder.or(
                        builder.equal(root.get("senderParticipant").get("id"), participantScope),
                        builder.equal(root.get("destinationParticipant").get("id"), participantScope)
                ));
            }

            if (criteria.senderParticipantId() != null) {
                predicates.add(builder.equal(root.get("senderParticipant").get("id"), criteria.senderParticipantId()));
            }

            if (criteria.destinationParticipantId() != null) {
                predicates.add(builder.equal(
                        root.get("destinationParticipant").get("id"), criteria.destinationParticipantId()));
            }

            if (criteria.status() != null) {
                predicates.add(builder.equal(root.get("status"), criteria.status()));
            }

            if (criteria.currency() != null && !criteria.currency().isBlank()) {
                predicates.add(builder.equal(root.get("currency"), criteria.currency().trim().toUpperCase()));
            }

            if (criteria.from() != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("createdAt"), criteria.from()));
            }

            if (criteria.to() != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get("createdAt"), criteria.to()));
            }

            if (criteria.reference() != null && !criteria.reference().isBlank()) {
                predicates.add(builder.like(
                        builder.upper(root.get("paymentReference")),
                        "%" + escapeLike(criteria.reference().trim().toUpperCase()) + "%",
                        '\\'
                ));
            }

            return builder.and(predicates.toArray(new Predicate[0]));
        };
    }

    /** Makes {@code %}, {@code _} and the escape character literal inside a LIKE pattern. */
    static String escapeLike(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}

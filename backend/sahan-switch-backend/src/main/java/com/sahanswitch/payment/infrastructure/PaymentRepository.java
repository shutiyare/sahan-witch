package com.sahanswitch.payment.infrastructure;

import com.sahanswitch.payment.domain.Payment;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID>, JpaSpecificationExecutor<Payment> {

    Optional<Payment> findByPaymentReference(String paymentReference);

    /** Loads the payment together with both participants, for work done after the session closes. */
    @EntityGraph(attributePaths = {"senderParticipant", "destinationParticipant"})
    Optional<Payment> findWithParticipantsById(UUID id);

    /** Idempotency lookup: the pair (sender, key) is unique in the database. */
    Optional<Payment> findBySenderParticipant_IdAndIdempotencyKey(UUID senderParticipantId, String idempotencyKey);

    /** Task 1: is this participant the sender or the receiver of the payment? */
    @Query("""
            select count(p) > 0 from Payment p
            where p.id = :paymentId
              and (p.senderParticipant.id = :participantId
                   or p.destinationParticipant.id = :participantId)
            """)
    boolean isParticipantInvolved(
            @Param("paymentId") UUID paymentId,
            @Param("participantId") UUID participantId
    );

    // ---------------------------------------------------------------- Task 3: analytics aggregates

    /** Rows of [currency, status, count, sum(amount)] for payments created since the given time. */
    @Query("""
            select p.currency, p.status, count(p), coalesce(sum(p.amount), 0)
            from Payment p
            where p.createdAt >= :since
            group by p.currency, p.status
            """)
    List<Object[]> aggregateByCurrencyAndStatus(@Param("since") Instant since);

    /** Rows of [destinationParticipantId, status, count]: how each receiving participant performed. */
    @Query("""
            select p.destinationParticipant.id, p.status, count(p)
            from Payment p
            where p.createdAt >= :since and p.destinationParticipant is not null
            group by p.destinationParticipant.id, p.status
            """)
    List<Object[]> aggregateByDestinationAndStatus(@Param("since") Instant since);

    /**
     * Rows of [epochSecondOfHourStart, status, count] for the hourly trend chart. Native SQL
     * because {@code date_trunc} has no portable JPQL equivalent. The hour boundary is computed
     * in UTC so it does not depend on the database server's time zone.
     */
    @Query(value = """
            select cast(extract(epoch from date_trunc('hour', created_at at time zone 'UTC')
                                 at time zone 'UTC') as bigint) as hour_start,
                   status,
                   count(*) as total
            from payments
            where created_at >= :since
            group by 1, 2
            order by 1
            """, nativeQuery = true)
    List<Object[]> aggregateHourly(@Param("since") Instant since);
}

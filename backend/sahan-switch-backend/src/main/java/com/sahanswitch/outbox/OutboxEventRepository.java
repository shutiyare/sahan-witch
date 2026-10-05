package com.sahanswitch.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Claims the oldest PENDING rows for this transaction.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} makes it safe to run several instances of the
     * application: a row locked by one relay is simply skipped by the others, so no event is
     * published twice by concurrent relays and none waits for another's lock.
     * Must be called inside a transaction.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status = 'PENDING'
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockPending(@Param("batchSize") int batchSize);

    long countByStatus(OutboxStatus status);

    List<OutboxEvent> findByAggregateIdOrderByCreatedAtAsc(UUID aggregateId);
}

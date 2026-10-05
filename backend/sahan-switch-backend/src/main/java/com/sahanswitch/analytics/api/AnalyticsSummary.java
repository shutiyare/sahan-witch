package com.sahanswitch.analytics.api;

import com.sahanswitch.participant.domain.ParticipantType;
import com.sahanswitch.payment.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Task 3: everything the operations dashboard shows, in one response.
 *
 * <p>A payment is "settled" once it is COMPLETED or FAILED. In-flight payments (ACCEPTED,
 * PROCESSING, PENDING) are counted in volumes but not in success rates, because their outcome
 * is not known yet.
 */
public record AnalyticsSummary(
        Instant from,
        Instant to,
        int windowHours,
        Totals totals,
        List<CurrencyStatusBucket> byCurrencyAndStatus,
        List<ParticipantStats> participants,
        List<HourlyBucket> hourly,
        long activeParticipants,
        List<CircuitBreakerInfo> circuitBreakers
) {

    /**
     * @param successRatePercent completed / (completed + failed) * 100; null while nothing has settled
     * @param settledValue       sum of COMPLETED amounts per currency (money that actually moved)
     */
    public record Totals(
            long transactionCount,
            long completed,
            long failed,
            long inFlight,
            Double successRatePercent,
            List<CurrencyValue> settledValue
    ) {
    }

    public record CurrencyValue(String currency, BigDecimal value) {
    }

    /** Volume and value for one (currency, status) pair. */
    public record CurrencyStatusBucket(String currency, PaymentStatus status, long count, BigDecimal totalAmount) {
    }

    /** Outcome of payments RECEIVED by one participant (the party that accepts or rejects them). */
    public record ParticipantStats(
            UUID participantId,
            String code,
            String name,
            ParticipantType type,
            long total,
            long completed,
            long failed,
            long inFlight,
            Double successRatePercent,
            Double failureRatePercent
    ) {
    }

    /** One hour of the trend chart; every hour of the window is present, with zeros when idle. */
    public record HourlyBucket(Instant hour, long total, long completed, long failed, long processing) {
    }

    /**
     * @param state              CLOSED (healthy), OPEN (calls blocked), HALF_OPEN (probing); CLOSED when the
     *                           participant has not been called yet
     * @param failureRatePercent null until the breaker has seen enough calls to compute one
     */
    public record CircuitBreakerInfo(
            UUID participantId,
            String participantCode,
            String participantName,
            String state,
            Double failureRatePercent,
            int bufferedCalls,
            long notPermittedCalls
    ) {
    }
}

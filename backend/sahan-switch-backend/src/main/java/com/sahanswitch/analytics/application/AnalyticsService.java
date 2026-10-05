package com.sahanswitch.analytics.application;

import com.sahanswitch.analytics.api.AnalyticsSummary;
import com.sahanswitch.analytics.api.AnalyticsSummary.CircuitBreakerInfo;
import com.sahanswitch.analytics.api.AnalyticsSummary.CurrencyStatusBucket;
import com.sahanswitch.analytics.api.AnalyticsSummary.CurrencyValue;
import com.sahanswitch.analytics.api.AnalyticsSummary.HourlyBucket;
import com.sahanswitch.analytics.api.AnalyticsSummary.ParticipantStats;
import com.sahanswitch.analytics.api.AnalyticsSummary.Totals;
import com.sahanswitch.participant.domain.Participant;
import com.sahanswitch.participant.domain.ParticipantStatus;
import com.sahanswitch.participant.infrastructure.ParticipantRepository;
import com.sahanswitch.payment.domain.PaymentStatus;
import com.sahanswitch.payment.infrastructure.PaymentRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Task 3: operational metrics for the dashboard. Read-only; every figure is computed by the
 * database (GROUP BY), the application only reshapes the rows.
 */
@Service
public class AnalyticsService {

    public static final int DEFAULT_WINDOW_HOURS = 24;
    public static final int MAX_WINDOW_HOURS = 24 * 7;

    private final PaymentRepository paymentRepository;
    private final ParticipantRepository participantRepository;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final Clock clock;

    public AnalyticsService(
            PaymentRepository paymentRepository,
            ParticipantRepository participantRepository,
            CircuitBreakerRegistry circuitBreakerRegistry,
            Clock clock
    ) {
        this.paymentRepository = paymentRepository;
        this.participantRepository = participantRepository;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AnalyticsSummary summary(int requestedHours) {

        if (requestedHours < 1 || requestedHours > MAX_WINDOW_HOURS) {
            throw new IllegalArgumentException("hours must be between 1 and " + MAX_WINDOW_HOURS);
        }

        Instant now = clock.instant();
        Instant since = now.minus(Duration.ofHours(requestedHours));

        List<CurrencyStatusBucket> buckets = currencyBuckets(since);

        List<Participant> participants = participantRepository.findAll();

        return new AnalyticsSummary(
                since,
                now,
                requestedHours,
                totals(buckets),
                buckets,
                participantStats(since, participants),
                hourly(since, now),
                participantRepository.countByStatus(ParticipantStatus.ACTIVE),
                circuitBreakers(participants)
        );
    }

    // ------------------------------------------------------------------ volume and value

    private List<CurrencyStatusBucket> currencyBuckets(Instant since) {
        return paymentRepository.aggregateByCurrencyAndStatus(since).stream()
                .map(row -> new CurrencyStatusBucket(
                        (String) row[0],
                        (PaymentStatus) row[1],
                        ((Number) row[2]).longValue(),
                        toBigDecimal(row[3])))
                .sorted(Comparator.comparing(CurrencyStatusBucket::currency)
                        .thenComparing(bucket -> bucket.status().ordinal()))
                .toList();
    }

    private Totals totals(List<CurrencyStatusBucket> buckets) {

        long all = 0;
        long completed = 0;
        long failed = 0;
        Map<String, BigDecimal> settledValue = new TreeMap<>();

        for (CurrencyStatusBucket bucket : buckets) {
            all += bucket.count();
            if (bucket.status() == PaymentStatus.COMPLETED) {
                completed += bucket.count();
                settledValue.merge(bucket.currency(), bucket.totalAmount(), BigDecimal::add);
            } else if (bucket.status() == PaymentStatus.FAILED) {
                failed += bucket.count();
            }
        }

        List<CurrencyValue> values = settledValue.entrySet().stream()
                .map(entry -> new CurrencyValue(entry.getKey(), entry.getValue()))
                .toList();

        return new Totals(all, completed, failed, all - completed - failed, rate(completed, completed + failed), values);
    }

    // ------------------------------------------------------------------ per participant

    private List<ParticipantStats> participantStats(Instant since, List<Participant> participants) {

        record Counters(long completed, long failed, long inFlight) {
            Counters add(PaymentStatus status, long count) {
                return switch (status) {
                    case COMPLETED -> new Counters(completed + count, failed, inFlight);
                    case FAILED -> new Counters(completed, failed + count, inFlight);
                    default -> new Counters(completed, failed, inFlight + count);
                };
            }
        }

        Map<UUID, Counters> byParticipant = new HashMap<>();

        for (Object[] row : paymentRepository.aggregateByDestinationAndStatus(since)) {
            byParticipant.merge(
                    (UUID) row[0],
                    new Counters(0, 0, 0).add((PaymentStatus) row[1], ((Number) row[2]).longValue()),
                    (left, right) -> new Counters(
                            left.completed() + right.completed(),
                            left.failed() + right.failed(),
                            left.inFlight() + right.inFlight()));
        }

        List<ParticipantStats> stats = new ArrayList<>();

        for (Participant participant : participants) {
            Counters counters = byParticipant.get(participant.getId());
            if (counters == null) {
                continue; // received nothing in the window
            }
            long settled = counters.completed() + counters.failed();
            Double success = rate(counters.completed(), settled);
            Double failure = rate(counters.failed(), settled);
            stats.add(new ParticipantStats(
                    participant.getId(), participant.getCode(), participant.getName(), participant.getType(),
                    settled + counters.inFlight(), counters.completed(), counters.failed(), counters.inFlight(),
                    success, failure));
        }

        stats.sort(Comparator.comparingLong(ParticipantStats::total).reversed()
                .thenComparing(ParticipantStats::code));

        return stats;
    }

    // ------------------------------------------------------------------ hourly trend

    private List<HourlyBucket> hourly(Instant since, Instant now) {

        long firstHour = since.truncatedTo(ChronoUnit.HOURS).getEpochSecond();
        long lastHour = now.truncatedTo(ChronoUnit.HOURS).getEpochSecond();

        // every hour of the window, zero-filled, so the chart has no gaps
        Map<Long, long[]> hours = new LinkedHashMap<>();
        for (long hour = firstHour; hour <= lastHour; hour += 3600) {
            hours.put(hour, new long[4]); // total, completed, failed, processing
        }

        for (Object[] row : paymentRepository.aggregateHourly(since)) {
            long[] counters = hours.computeIfAbsent(((Number) row[0]).longValue(), key -> new long[4]);
            PaymentStatus status = PaymentStatus.valueOf((String) row[1]);
            long count = ((Number) row[2]).longValue();

            counters[0] += count;
            switch (status) {
                case COMPLETED -> counters[1] += count;
                case FAILED -> counters[2] += count;
                case PROCESSING, ACCEPTED, PENDING -> counters[3] += count;
            }
        }

        return hours.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new HourlyBucket(
                        Instant.ofEpochSecond(entry.getKey()),
                        entry.getValue()[0], entry.getValue()[1], entry.getValue()[2], entry.getValue()[3]))
                .toList();
    }

    // ------------------------------------------------------------------ circuit breakers

    private List<CircuitBreakerInfo> circuitBreakers(List<Participant> participants) {

        return participants.stream()
                .filter(Participant::isActive)
                .sorted(Comparator.comparing(Participant::getCode))
                .map(participant -> {
                    // find(), not circuitBreaker(): reading metrics must not create breakers
                    return circuitBreakerRegistry.find(participant.getCode())
                            .map(breaker -> describe(participant, breaker))
                            .orElseGet(() -> new CircuitBreakerInfo(
                                    participant.getId(), participant.getCode(), participant.getName(),
                                    CircuitBreaker.State.CLOSED.name(), null, 0, 0));
                })
                .toList();
    }

    private CircuitBreakerInfo describe(Participant participant, CircuitBreaker breaker) {
        CircuitBreaker.Metrics metrics = breaker.getMetrics();
        float failureRate = metrics.getFailureRate();
        return new CircuitBreakerInfo(
                participant.getId(),
                participant.getCode(),
                participant.getName(),
                breaker.getState().name(),
                failureRate < 0 ? null : (double) failureRate, // -1 means "not enough calls yet"
                metrics.getNumberOfBufferedCalls(),
                metrics.getNumberOfNotPermittedCalls());
    }

    // ------------------------------------------------------------------ helpers

    /** Percentage with one decimal, or null when the denominator is zero. */
    static Double rate(long part, long whole) {
        if (whole == 0) {
            return null;
        }
        return Math.round(part * 1000.0 / whole) / 10.0;
    }

    private static BigDecimal toBigDecimal(Object value) {
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
    }
}

package com.sahanswitch.analytics.application;

import com.sahanswitch.analytics.api.AnalyticsSummary;
import com.sahanswitch.participant.domain.Participant;
import com.sahanswitch.participant.domain.ParticipantStatus;
import com.sahanswitch.participant.domain.ParticipantType;
import com.sahanswitch.participant.infrastructure.ParticipantRepository;
import com.sahanswitch.payment.domain.PaymentStatus;
import com.sahanswitch.payment.infrastructure.PaymentRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalyticsServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-05T10:30:00Z");

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final ParticipantRepository participantRepository = mock(ParticipantRepository.class);
    private final CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();

    private AnalyticsService service;
    private Participant bankA;
    private Participant bankB;
    private Participant sleeper;

    @BeforeEach
    void setUp() {
        service = new AnalyticsService(paymentRepository, participantRepository, registry,
                Clock.fixed(NOW, ZoneOffset.UTC));

        bankA = participant("BANKA", true);
        bankB = participant("BANKB", true);
        sleeper = participant("ZZZ", true);

        when(participantRepository.findAll()).thenReturn(List.of(bankB, sleeper, bankA));
        when(participantRepository.countByStatus(ParticipantStatus.ACTIVE)).thenReturn(3L);
        when(paymentRepository.aggregateByCurrencyAndStatus(any())).thenReturn(List.of());
        when(paymentRepository.aggregateByDestinationAndStatus(any())).thenReturn(List.of());
        when(paymentRepository.aggregateHourly(any())).thenReturn(List.of());
    }

    private Participant participant(String code, boolean active) {
        Participant participant = mock(Participant.class);
        when(participant.getId()).thenReturn(UUID.randomUUID());
        when(participant.getCode()).thenReturn(code);
        when(participant.getName()).thenReturn(code + " bank");
        when(participant.getType()).thenReturn(ParticipantType.BANK);
        when(participant.isActive()).thenReturn(active);
        return participant;
    }

    private static Object[] row(Object... values) {
        return values;
    }

    @Test
    void anEmptyWindowGivesZerosNullRatesAndAFullHourlyAxis() {
        AnalyticsSummary summary = service.summary(24);

        assertEquals(NOW.minusSeconds(24 * 3600), summary.from());
        assertEquals(NOW, summary.to());
        assertEquals(0, summary.totals().transactionCount());
        assertNull(summary.totals().successRatePercent(), "no settled payments -> no rate, not 0 % or NaN");
        assertTrue(summary.participants().isEmpty());
        // 10:30 minus 24 h = 10:30 yesterday -> buckets 10:00 yesterday ... 10:00 today
        assertEquals(25, summary.hourly().size());
        assertTrue(summary.hourly().stream().allMatch(bucket -> bucket.total() == 0));
        assertEquals(Instant.parse("2026-10-04T10:00:00Z"), summary.hourly().get(0).hour());
        assertEquals(Instant.parse("2026-10-05T10:00:00Z"), summary.hourly().get(24).hour());
    }

    @Test
    void totalsAndSettledValueAreComputedPerCurrency() {
        when(paymentRepository.aggregateByCurrencyAndStatus(any())).thenReturn(List.of(
                row("USD", PaymentStatus.COMPLETED, 3L, new BigDecimal("150.50")),
                row("USD", PaymentStatus.FAILED, 1L, new BigDecimal("999.00")),
                row("EUR", PaymentStatus.COMPLETED, 1L, new BigDecimal("10.00")),
                row("USD", PaymentStatus.PROCESSING, 2L, new BigDecimal("5.00"))
        ));

        AnalyticsSummary.Totals totals = service.summary(24).totals();

        assertEquals(7, totals.transactionCount());
        assertEquals(4, totals.completed());
        assertEquals(1, totals.failed());
        assertEquals(2, totals.inFlight());
        assertEquals(80.0, totals.successRatePercent(), "in-flight payments are not part of the rate");

        // failed money did not move, so it is not in the settled value
        assertEquals(List.of(
                new AnalyticsSummary.CurrencyValue("EUR", new BigDecimal("10.00")),
                new AnalyticsSummary.CurrencyValue("USD", new BigDecimal("150.50"))), totals.settledValue());
    }

    @Test
    void participantSuccessAndFailureRatesCountReceivedPaymentsOnly() {
        UUID idA = bankA.getId();
        UUID idB = bankB.getId();
        List<Object[]> rows = List.of(
                row(idA, PaymentStatus.COMPLETED, 2L),
                row(idA, PaymentStatus.FAILED, 1L),
                row(idA, PaymentStatus.PROCESSING, 4L),
                row(idB, PaymentStatus.COMPLETED, 9L)
        );
        when(paymentRepository.aggregateByDestinationAndStatus(any())).thenReturn(rows);

        List<AnalyticsSummary.ParticipantStats> stats = service.summary(24).participants();

        assertEquals(2, stats.size(), "participants that received nothing are left out");
        assertEquals("BANKB", stats.get(0).code(), "busiest first: B received 9, A received 7");

        AnalyticsSummary.ParticipantStats a = stats.stream().filter(s -> s.code().equals("BANKA")).findFirst().orElseThrow();
        assertEquals(7, a.total());
        assertEquals(66.7, a.successRatePercent());
        assertEquals(33.3, a.failureRatePercent());
        assertEquals(4, a.inFlight());

        AnalyticsSummary.ParticipantStats b = stats.stream().filter(s -> s.code().equals("BANKB")).findFirst().orElseThrow();
        assertEquals(100.0, b.successRatePercent());
        assertEquals(0.0, b.failureRatePercent());
    }

    @Test
    void hourlyBucketsAreFilledFromTheDatabaseRows() {
        long hour = Instant.parse("2026-10-05T09:00:00Z").getEpochSecond();
        when(paymentRepository.aggregateHourly(any())).thenReturn(List.of(
                row(hour, "COMPLETED", 5L),
                row(hour, "FAILED", 2L),
                row(hour, "PROCESSING", 1L)
        ));

        AnalyticsSummary.HourlyBucket bucket = service.summary(24).hourly().stream()
                .filter(b -> b.hour().equals(Instant.parse("2026-10-05T09:00:00Z")))
                .findFirst().orElseThrow();

        assertEquals(8, bucket.total());
        assertEquals(5, bucket.completed());
        assertEquals(2, bucket.failed());
        assertEquals(1, bucket.processing());
    }

    @Test
    void circuitBreakerStatesComeFromTheRegistryAndDefaultToClosed() {
        CircuitBreaker breaker = registry.circuitBreaker("BANKA");
        breaker.transitionToOpenState();

        List<AnalyticsSummary.CircuitBreakerInfo> infos = service.summary(24).circuitBreakers();

        assertEquals(List.of("BANKA", "BANKB", "ZZZ"), infos.stream().map(i -> i.participantCode()).toList(),
                "sorted by code");
        assertEquals("OPEN", infos.get(0).state());
        assertEquals("CLOSED", infos.get(1).state(), "never called yet -> CLOSED");
        assertNull(infos.get(1).failureRatePercent());
        assertTrue(registry.find("BANKB").isEmpty(), "reading analytics must not create breakers");
    }

    @Test
    void inactiveParticipantsAreNotListedAmongTheCircuitBreakers() {
        Participant retired = participant("OLD", false);
        when(participantRepository.findAll()).thenReturn(List.of(bankA, retired));

        List<String> codes = service.summary(24).circuitBreakers().stream().map(i -> i.participantCode()).toList();

        assertEquals(List.of("BANKA"), codes);
    }

    @Test
    void theWindowMustBeBetweenOneHourAndAWeek() {
        assertThrows(IllegalArgumentException.class, () -> service.summary(0));
        assertThrows(IllegalArgumentException.class, () -> service.summary(-5));
        assertThrows(IllegalArgumentException.class, () -> service.summary(24 * 7 + 1));
        service.summary(1);
        service.summary(24 * 7);
    }

    @Test
    void ratesAreRoundedToOneDecimalAndNullWithoutData() {
        assertNull(AnalyticsService.rate(0, 0));
        assertEquals(33.3, AnalyticsService.rate(1, 3));
        assertEquals(66.7, AnalyticsService.rate(2, 3));
        assertEquals(100.0, AnalyticsService.rate(5, 5));
        assertEquals(0.0, AnalyticsService.rate(0, 5));
    }
}

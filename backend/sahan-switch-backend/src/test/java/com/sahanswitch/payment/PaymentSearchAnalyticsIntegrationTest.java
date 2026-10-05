package com.sahanswitch.payment;

import com.sahanswitch.support.ApiTestSupport;
import com.sahanswitch.support.SyncModeIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 3 against a real database: filters, paging, sorting, input safety, and the analytics
 * figures. Every test creates its own participants, so assertions are scoped to them and do not
 * depend on what other tests left in the table.
 */
@SyncModeIntegrationTest
class PaymentSearchAnalyticsIntegrationTest extends ApiTestSupport {

    private String pay(Participant sender, Participant destination, String account, String amount, String currency) {
        Response response = postJson("/api/v1/payments", jsonPayment(sender, destination, account, amount, currency),
                "Idempotency-Key", "k-" + UUID.randomUUID());
        assertEquals(201, response.status(), response.body());
        return response.json("$.id");
    }

    /** A JSONPath filter always returns a list; this reads the number of its first element. */
    private static Number first(Response response, String path) {
        List<?> values = response.json(path);
        return (Number) values.get(0);
    }

    private int total(Response search) {
        assertEquals(200, search.status(), search.body());
        return ((Number) search.json("$.page.totalElements")).intValue();
    }

    // ================================================================ search

    @Test
    void searchFiltersByEveryCriterionAndPages() {
        Participant sender = createParticipant("SQS");
        Participant destination = createParticipant("SQD");

        pay(sender, destination, "ACC-1", "10.00", "USD");
        pay(sender, destination, "ACC-1", "20.00", "USD");
        pay(sender, destination, "ACC-1", "30.00", "EUR");
        pay(sender, destination, "FAIL-1", "40.00", "USD");   // rejected by the participant -> FAILED

        String mine = "senderParticipantId=" + sender.id();

        assertEquals(4, total(get("/api/v1/payments/search?" + mine)));
        assertEquals(4, total(get("/api/v1/payments/search?destinationParticipantId=" + destination.id())));
        assertEquals(3, total(get("/api/v1/payments/search?" + mine + "&status=COMPLETED")));
        assertEquals(1, total(get("/api/v1/payments/search?" + mine + "&status=FAILED")));
        assertEquals(1, total(get("/api/v1/payments/search?" + mine + "&currency=EUR")));
        assertEquals(3, total(get("/api/v1/payments/search?" + mine + "&currency=usd")), "currency is case-insensitive");
        assertEquals(1, total(get("/api/v1/payments/search?" + mine + "&status=COMPLETED&currency=EUR")));
        assertEquals(0, total(get("/api/v1/payments/search?" + mine + "&status=PROCESSING")));

        // paging
        Response firstPage = get("/api/v1/payments/search?" + mine + "&size=3&page=0");
        assertEquals(3, ((List<?>) firstPage.json("$.content")).size());
        assertEquals(4, ((Number) firstPage.json("$.page.totalElements")).intValue());
        assertEquals(2, ((Number) firstPage.json("$.page.totalPages")).intValue());
        assertEquals(1, ((List<?>) get("/api/v1/payments/search?" + mine + "&size=3&page=1").json("$.content")).size());
    }

    @Test
    void searchByDateRangeUsesDaysAndInstants() {
        Participant sender = createParticipant("SDS");
        Participant destination = createParticipant("SDD");
        pay(sender, destination, "ACC-1", "10.00", "USD");

        String mine = "senderParticipantId=" + sender.id();

        assertEquals(1, total(get("/api/v1/payments/search?" + mine + "&fromDate=2000-01-01&toDate=2999-12-31")));
        assertEquals(0, total(get("/api/v1/payments/search?" + mine + "&toDate=2000-01-01")));
        assertEquals(0, total(get("/api/v1/payments/search?" + mine + "&fromDate=2999-01-01T00:00:00Z")));

        // a whole-day 'toDate' includes everything created on that day
        String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        assertEquals(1, total(get("/api/v1/payments/search?" + mine + "&fromDate=" + today + "&toDate=" + today)));

        assertEquals(400, get("/api/v1/payments/search?fromDate=not-a-date").status());
        assertEquals(400, get("/api/v1/payments/search?fromDate=2026-02-01&toDate=2026-01-01").status(),
                "an inverted range is a client error");
    }

    @Test
    void searchByReferenceIsAPartialMatchAndTreatsWildcardsAsText() {
        Participant sender = createParticipant("SRS");
        Participant destination = createParticipant("SRD");
        String id = pay(sender, destination, "ACC-1", "10.00", "USD");
        String reference = get("/api/v1/payments/" + id).json("$.paymentReference");

        String mine = "senderParticipantId=" + sender.id();

        assertEquals(1, total(get("/api/v1/payments/search?" + mine + "&reference=" + reference.substring(3, 10))));
        assertEquals(0, total(get("/api/v1/payments/search?" + mine + "&reference=%25")),
                "'%' is a literal character, not 'match everything'");
        assertEquals(0, total(get("/api/v1/payments/search?" + mine + "&reference=_")),
                "'_' is a literal character, not 'any single character'");
    }

    @Test
    void searchSortsByWhitelistedPropertiesOnly() {
        Participant sender = createParticipant("SOS");
        Participant destination = createParticipant("SOD");
        pay(sender, destination, "ACC-1", "10.00", "USD");
        pay(sender, destination, "ACC-1", "30.00", "USD");
        pay(sender, destination, "ACC-1", "20.00", "USD");

        String mine = "senderParticipantId=" + sender.id();

        Response ascending = get("/api/v1/payments/search?" + mine + "&sort=amount,asc");
        assertEquals(10.0, ((Number) ascending.json("$.content[0].amount")).doubleValue());
        assertEquals(30.0, ((Number) ascending.json("$.content[2].amount")).doubleValue());

        Response descending = get("/api/v1/payments/search?" + mine + "&sort=amount,desc");
        assertEquals(30.0, ((Number) descending.json("$.content[0].amount")).doubleValue());

        // not on the whitelist -> a clear 400, never a 500 from the persistence layer
        Response bad = get("/api/v1/payments/search?" + mine + "&sort=apiKeyHash,asc");
        assertEquals(400, bad.status(), bad.body());
        assertEquals(400, get("/api/v1/payments/search?" + mine + "&sort=sender.apiKeyHash").status());
    }

    @Test
    void pageSizeIsCapped() {
        Response response = get("/api/v1/payments/search?size=100000");

        assertEquals(200, response.status());
        assertTrue(((Number) response.json("$.page.size")).intValue() <= 100);
    }

    @Test
    void searchRejectsAMalformedStatus() {
        assertEquals(400, get("/api/v1/payments/search?status=NOT_A_STATUS").status());
        assertEquals(400, get("/api/v1/payments/search?senderParticipantId=not-a-uuid").status());
    }

    // ================================================================ analytics

    @Test
    void analyticsReportsVolumeValueAndRatesPerParticipant() {
        Participant sender = createParticipant("ANS");
        Participant destination = createParticipant("AND");

        pay(sender, destination, "ACC-1", "100.00", "USD");
        pay(sender, destination, "ACC-1", "50.50", "USD");
        pay(sender, destination, "ACC-1", "10.00", "EUR");
        pay(sender, destination, "FAIL-1", "999.00", "USD");

        Response response = get("/api/v1/analytics/summary");
        assertEquals(200, response.status(), response.body());

        // --- this destination's own row: 3 completed, 1 failed -> 75.0 % / 25.0 % ---------------
        String path = "$.participants[?(@.code=='" + destination.code() + "')]";
        List<Object> rows = response.json(path);
        assertEquals(1, rows.size(), "the destination appears exactly once");

        assertEquals(4, first(response, path + ".total").intValue());
        assertEquals(3, first(response, path + ".completed").intValue());
        assertEquals(1, first(response, path + ".failed").intValue());
        assertEquals(75.0, first(response, path + ".successRatePercent").doubleValue());
        assertEquals(25.0, first(response, path + ".failureRatePercent").doubleValue());

        // --- window and shape ------------------------------------------------------------------
        assertEquals(24, ((Number) response.json("$.windowHours")).intValue());
        assertNotNull(response.json("$.totals.successRatePercent"));
        assertTrue(((Number) response.json("$.totals.transactionCount")).longValue() >= 4);
        assertTrue(((List<?>) response.json("$.byCurrencyAndStatus")).size() >= 3);

        // the trend has one bucket per hour of the window (24 or 25 depending on the clock)
        int hours = ((List<?>) response.json("$.hourly")).size();
        assertTrue(hours >= 24 && hours <= 25, "hourly buckets: " + hours);

        // --- circuit breaker block lists every active participant -------------------------------
        List<Object> breaker = response.json("$.circuitBreakers[?(@.participantCode=='" + destination.code() + "')]");
        assertEquals(1, breaker.size());
        assertEquals("CLOSED", ((List<?>) response.json(
                "$.circuitBreakers[?(@.participantCode=='" + destination.code() + "')].state")).get(0));
    }

    @Test
    void analyticsAcceptsAWindowAndRejectsNonsense() {
        assertEquals(6, ((Number) get("/api/v1/analytics/summary?hours=6").json("$.windowHours")).intValue());

        assertEquals(400, get("/api/v1/analytics/summary?hours=0").status());
        assertEquals(400, get("/api/v1/analytics/summary?hours=100000").status());
        assertEquals(400, get("/api/v1/analytics/summary?hours=abc").status());
    }
}

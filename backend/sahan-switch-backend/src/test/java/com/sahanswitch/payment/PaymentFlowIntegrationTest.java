package com.sahanswitch.payment;

import com.sahanswitch.support.ApiTestSupport;
import com.sahanswitch.support.SyncModeIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 6.2: the complete flow against a real PostgreSQL, through the real HTTP API.
 *
 * <pre>
 *   participant creation -> pacs.008 (or JSON) -> idempotency -> routing
 *        -> state transitions -> audit rows -> pacs.002 report
 * </pre>
 *
 * Runs on a Testcontainers PostgreSQL, or on the database named by {@code TEST_DB_URL};
 * skipped when neither is available (see {@link PostgresTestSupport}).
 *
 * <p>The mock participant connector is steered by the destination account:
 * {@code FAIL-...} is rejected, {@code TIMEOUT-...} never answers. Retries wait only 1 ms and
 * the circuit breaker is tightened (needs 3 calls) so the failure paths run quickly.
 */
@SyncModeIntegrationTest
class PaymentFlowIntegrationTest extends ApiTestSupport {

    // ================================================================ the main flow, ISO 20022

    @Test
    void isoPaymentFlowFromParticipantsToAuditTrail() {
        Participant sender = createParticipant("SNDR");
        Participant destination = createParticipant("DEST");
        String uetr = UUID.randomUUID().toString();
        String e2e = "E2E-" + UUID.randomUUID().toString().substring(0, 8);
        String message = pacs008(sender, destination, uetr, e2e, "ACC-DST-1", "100.50");

        // --- 1. submit the pacs.008 --------------------------------------------------------
        Response created = postXml("/api/v1/iso20022/pacs008", message, "X-Correlation-Id", "flow-test-001");

        assertEquals(201, created.status(), created.body());
        assertEquals("flow-test-001", created.headers().getFirst("X-Correlation-Id"));
        assertTrue(created.headers().getContentType().isCompatibleWith(MediaType.APPLICATION_XML));
        assertTrue(created.body().contains("urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10"), created.body());
        assertEquals("ACSC", xmlValue(created.body(), "TxSts"), "payment must be settled");
        assertEquals(uetr, xmlValue(created.body(), "OrgnlUETR"));
        assertEquals(e2e, xmlValue(created.body(), "OrgnlEndToEndId"));

        // --- 2. the stored payment ------------------------------------------------------
        String reference = xmlValue(created.body(), "OrgnlTxId");
        Response payment = get("/api/v1/payments/by-reference/" + reference);
        assertEquals(200, payment.status(), payment.body());

        String paymentId = payment.json("$.id");
        assertEquals("COMPLETED", payment.json("$.status"));
        assertNotNull(payment.json("$.externalReference"));
        assertEquals(sender.id(), payment.json("$.senderParticipantId"));
        assertEquals(destination.id(), payment.json("$.destinationParticipantId"));
        assertEquals(uetr, payment.json("$.uetr"));
        assertEquals(e2e, payment.json("$.endToEndId"));
        assertEquals("Alice Debtor", payment.json("$.debtorName"));
        assertEquals("Bob Creditor", payment.json("$.creditorName"));

        // --- 3. database state ------------------------------------------------------------
        assertEquals("COMPLETED", jdbc.queryForObject(
                "select status from payments where id = ?::uuid", String.class, paymentId));
        Long version = jdbc.queryForObject(
                "select version from payments where id = ?::uuid", Long.class, paymentId);
        assertTrue(version != null && version > 0,
                "optimistic-lock version must have been incremented by the status updates");

        // --- 4. audit trail: one row per transition, all tied to the request's correlation id ---
        assertEquals(List.of("ACCEPTED", "PROCESSING", "COMPLETED"), auditStatuses(paymentId));

        Response audit = get("/api/v1/payments/" + paymentId + "/audit");
        Object firstPrevious = audit.json("$[0].previousStatus");
        assertNull(firstPrevious, "first entry has no previous status");
        assertEquals("ACCEPTED", audit.json("$[1].previousStatus"));
        assertEquals("PROCESSING", audit.json("$[2].previousStatus"));
        for (int i = 0; i < 3; i++) {
            assertEquals("flow-test-001", audit.json("$[" + i + "].correlationId"));
        }
        assertTrue(((String) audit.json("$[2].reason")).contains("externalReference="));

        // --- 5. replay: same message again -> 200, same payment, no new audit rows ------------
        Response replay = postXml("/api/v1/iso20022/pacs008", message);

        assertEquals(200, replay.status(), replay.body());
        assertEquals(reference, xmlValue(replay.body(), "OrgnlTxId"));
        assertEquals(3, auditStatuses(paymentId).size(), "a replay must not create audit rows");
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from payments where uetr = ?::uuid", Long.class, uetr));

        // --- 6. conflicting reuse of the UETR (used as idempotency key) -> 409 ---------------------
        Response conflict = postXml("/api/v1/iso20022/pacs008",
                pacs008(sender, destination, uetr, e2e, "ACC-DST-1", "999.00"));
        assertEquals(409, conflict.status(), conflict.body());

        // --- 7. the stored payment can be read back as standard ISO messages -----------------------
        Response pacs008Xml = get("/api/v1/payments/" + paymentId + "/pacs008");
        assertEquals(200, pacs008Xml.status());
        assertEquals(sender.code(), pacs008Xml.body().replaceAll("(?s).*<DbtrAgt>.*?<MmbId>(.*?)</MmbId>.*", "$1"));
        assertTrue(pacs008Xml.body().contains("<IntrBkSttlmAmt Ccy=\"USD\">100.5</IntrBkSttlmAmt>"), pacs008Xml.body());

        Response pacs002Xml = get("/api/v1/payments/" + paymentId + "/pacs002");
        assertEquals("ACSC", xmlValue(pacs002Xml.body(), "TxSts"));
    }

    // ================================================================ JSON flow + idempotency

    @Test
    void jsonPaymentIsIdempotentAndConflictsAreRejected() {
        Participant sender = createParticipant("SNDR");
        Participant destination = createParticipant("DEST");
        String key = "json-" + UUID.randomUUID();
        String body = jsonPayment(sender, destination, "ACC-DST-2", "250.00");

        Response first = postJson("/api/v1/payments", body, "Idempotency-Key", key);
        assertEquals(201, first.status(), first.body());
        assertEquals("COMPLETED", first.json("$.status"));
        assertNotNull(first.json("$.uetr"), "a UETR is generated for plain JSON payments");

        Response replay = postJson("/api/v1/payments", body, "Idempotency-Key", key);
        assertEquals(200, replay.status(), "a replay is 200 OK, not 201 Created");
        assertEquals((String) first.json("$.id"), replay.json("$.id"));

        Response sameValueOtherScale = postJson("/api/v1/payments",
                jsonPayment(sender, destination, "ACC-DST-2", "250.0000"), "Idempotency-Key", key);
        assertEquals(200, sameValueOtherScale.status());

        Response conflict = postJson("/api/v1/payments",
                jsonPayment(sender, destination, "ACC-DST-2", "251.00"), "Idempotency-Key", key);
        assertEquals(409, conflict.status());
        assertEquals("Idempotency Conflict", conflict.json("$.error"));

        assertEquals(3, auditStatuses(first.json("$.id")).size());
    }

    @Test
    void identicalParallelRequestsCreateExactlyOnePayment() throws Exception {
        Participant sender = createParticipant("SNDR");
        Participant destination = createParticipant("DEST");
        String key = "race-" + UUID.randomUUID();
        String body = jsonPayment(sender, destination, "ACC-DST-3", "77.00");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Response>> calls = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                calls.add(() -> postJson("/api/v1/payments", body, "Idempotency-Key", key));
            }

            int created = 0;
            int replayed = 0;
            java.util.Set<String> ids = new java.util.HashSet<>();

            for (Future<Response> future : pool.invokeAll(calls)) {
                Response response = future.get();
                if (response.status() == 201) {
                    created++;
                } else if (response.status() == 200) {
                    replayed++;
                } else {
                    throw new AssertionError("unexpected " + response.status() + ": " + response.body());
                }
                ids.add(response.json("$.id"));
            }

            assertEquals(1, created, "exactly one request creates the payment");
            assertEquals(threads - 1, replayed, "every other request is resolved as a replay");
            assertEquals(1, ids.size(), "all responses point at the same payment");
            assertEquals(1L, jdbc.queryForObject("select count(*) from payments where idempotency_key = ?",
                    Long.class, key));
        } finally {
            pool.shutdownNow();
        }
    }

    // ================================================================ failure paths

    @Test
    void paymentRejectedByParticipantEndsFailedWithReasonInPaymentAuditAndPacs002() {
        Participant sender = createParticipant("SNDR");
        Participant destination = createParticipant("DEST");

        Response response = postJson("/api/v1/payments",
                jsonPayment(sender, destination, "FAIL-ACC-9", "10.00"),
                "Idempotency-Key", "reject-" + UUID.randomUUID());

        assertEquals(201, response.status(), "the payment was created, the participant said no");
        assertEquals("FAILED", response.json("$.status"));
        assertTrue(((String) response.json("$.failureReason")).contains("Rejected by participant " + destination.code()));
        Object externalReference = response.json("$.externalReference");
        assertNull(externalReference, "a rejected payment has no external reference");

        String paymentId = response.json("$.id");
        assertEquals(List.of("ACCEPTED", "PROCESSING", "FAILED"), auditStatuses(paymentId));

        Response audit = get("/api/v1/payments/" + paymentId + "/audit");
        assertEquals((String) response.json("$.failureReason"), audit.json("$[2].reason"));

        Response report = get("/api/v1/payments/" + paymentId + "/pacs002");
        assertEquals("RJCT", xmlValue(report.body(), "TxSts"));
        assertEquals("NARR", xmlValue(report.body(), "Cd"));
    }

    @Test
    void unreachableParticipantIsRetriedThenCircuitOpensAndFailsFast() {
        Participant sender = createParticipant("SNDR");
        Participant destination = createParticipant("DEST");

        // First payment: 3 attempts, all time out -> the circuit breaker (needs 3 calls) opens.
        Response first = postJson("/api/v1/payments",
                jsonPayment(sender, destination, "TIMEOUT-ACC-1", "10.00"),
                "Idempotency-Key", "to1-" + UUID.randomUUID());

        assertEquals(201, first.status(), first.body());
        assertEquals("FAILED", first.json("$.status"));
        String firstReason = first.json("$.failureReason");
        assertTrue(firstReason.contains("Participant timeout"), firstReason);
        assertTrue(firstReason.contains("unavailable after 3 attempt(s)"), firstReason);

        // Second payment: the open circuit rejects it immediately.
        Response second = postJson("/api/v1/payments",
                jsonPayment(sender, destination, "TIMEOUT-ACC-2", "10.00"),
                "Idempotency-Key", "to2-" + UUID.randomUUID());

        assertEquals("FAILED", second.json("$.status"));
        String secondReason = second.json("$.failureReason");
        assertTrue(secondReason.contains("circuit breaker open"), secondReason);

        // The ISO report for a timeout uses the dedicated timeout reason code.
        Response report = get("/api/v1/payments/" + second.json("$.id") + "/pacs002");
        assertEquals("RJCT", xmlValue(report.body(), "TxSts"));
        assertEquals("AB05", xmlValue(report.body(), "Cd"));

        // A different, healthy participant is not affected by this one's open circuit.
        Participant healthy = createParticipant("HEAL");
        Response ok = postJson("/api/v1/payments",
                jsonPayment(sender, healthy, "ACC-OK", "10.00"), "Idempotency-Key", "ok-" + UUID.randomUUID());
        assertEquals("COMPLETED", ok.json("$.status"));
    }

    // ================================================================ validation / business rules

    @Test
    void invalidIsoMessageIsRejectedWithAllProblemsListed() {
        Participant sender = createParticipant("SNDR");
        Participant destination = createParticipant("DEST");

        // no UETR, negative amount
        Response response = postXml("/api/v1/iso20022/pacs008",
                pacs008(sender, destination, null, "E2E-BAD", "ACC-DST-4", "-5"));

        assertEquals(400, response.status(), response.body());
        assertEquals("Invalid ISO 20022 Message", response.json("$.error"));
        assertEquals("UETR is required", response.json("$.fieldErrors['PmtId/UETR']"));
        assertNotNull(response.json("$.fieldErrors['IntrBkSttlmAmt']"));
        assertEquals(0L, jdbc.queryForObject("select count(*) from payments where end_to_end_id = 'E2E-BAD'", Long.class));
    }

    @Test
    void jsonValidationErrorsAreReturnedPerField() {
        Response response = postJson("/api/v1/payments", "{\"amount\":-1,\"currency\":\"usd\"}",
                "Idempotency-Key", "bad-" + UUID.randomUUID());

        assertEquals(400, response.status(), response.body());
        assertEquals("Validation Failed", response.json("$.error"));
        assertNotNull(response.json("$.fieldErrors.senderParticipantId"));
        assertNotNull(response.json("$.fieldErrors.destinationParticipantId"));
        assertNotNull(response.json("$.fieldErrors.amount"));
        assertNotNull(response.json("$.fieldErrors.currency"));
    }

    @Test
    void inactiveDestinationIsRejectedAsUnprocessableAndNothingIsStored() {
        Participant sender = createParticipant("SNDR");
        Participant destination = createParticipant("DEST");

        Response deactivate = send(HttpMethod.PATCH, "/api/v1/participants/" + destination.id() + "/deactivate", null, null);
        assertEquals(200, deactivate.status());

        String key = "inactive-" + UUID.randomUUID();
        Response response = postJson("/api/v1/payments",
                jsonPayment(sender, destination, "ACC-DST-5", "10.00"), "Idempotency-Key", key);

        assertEquals(422, response.status(), response.body());
        assertEquals(0L, jdbc.queryForObject("select count(*) from payments where idempotency_key = ?", Long.class, key));
    }

    @Test
    void correlationIdIsGeneratedWhenTheClientSendsNone() {
        Response response = get("/api/v1/participants");

        assertNotNull(response.headers().getFirst("X-Correlation-Id"));
        assertTrue(response.headers().getFirst("X-Correlation-Id").length() >= 32);
    }

    // ================================================================ audit immutability (database level)

    @Test
    void auditRowsCannotBeChangedOrDeletedEvenWithDirectDatabaseAccess() {
        Participant sender = createParticipant("SNDR");
        Participant destination = createParticipant("DEST");
        Response payment = postJson("/api/v1/payments",
                jsonPayment(sender, destination, "ACC-DST-6", "10.00"), "Idempotency-Key", "imm-" + UUID.randomUUID());
        String paymentId = payment.json("$.id");

        DataAccessException update = assertThrows(DataAccessException.class, () -> jdbc.update(
                "update payment_audit_logs set reason = 'tampered' where payment_id = ?::uuid", paymentId));
        assertTrue(update.getMessage().contains("append-only"), update.getMessage());

        DataAccessException delete = assertThrows(DataAccessException.class, () -> jdbc.update(
                "delete from payment_audit_logs where payment_id = ?::uuid", paymentId));
        assertTrue(delete.getMessage().contains("append-only"), delete.getMessage());

        assertEquals(3, auditStatuses(paymentId).size());
    }
}

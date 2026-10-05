package com.sahanswitch.messaging;

import com.sahanswitch.support.ApiTestSupport;
import com.sahanswitch.support.RabbitTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 2 end to end with the real thing: PostgreSQL + RabbitMQ + the full Spring context in the
 * default {@code async} mode.
 *
 * <p>POST /payments returns immediately with ACCEPTED; the outbox relay publishes the routing
 * request, the consumer routes the payment, and the status moves on by itself. The queues are
 * created with a throw-away name prefix and deleted afterwards, so the test never touches a
 * developer's real queues. Skipped when no broker is reachable.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sahanswitch.routing.mode=async",
                "sahanswitch.outbox.enabled=true",
                "sahanswitch.outbox.poll-interval-ms=200",
                "sahanswitch.security.jwt.secret=" + ApiTestSupport.JWT_SECRET,
                "sahanswitch.security.admin.username=" + ApiTestSupport.ADMIN_USERNAME,
                "sahanswitch.security.admin.password=" + ApiTestSupport.ADMIN_PASSWORD,
                "sahanswitch.resilience.retry.initial-backoff=1ms",
                "sahanswitch.resilience.circuit-breaker.sliding-window-size=4",
                "sahanswitch.resilience.circuit-breaker.minimum-number-of-calls=3",
                "spring.jpa.show-sql=false"
        }
)
@EnabledIf(value = "com.sahanswitch.support.RabbitTestSupport#isAvailable",
        disabledReason = "Needs PostgreSQL (TEST_DB_URL / Docker) and a RabbitMQ broker on localhost:5672")
class AsyncPaymentFlowIntegrationTest extends ApiTestSupport {

    private static final String PREFIX = "it" + UUID.randomUUID().toString().substring(0, 8) + ".";

    @DynamicPropertySource
    static void broker(DynamicPropertyRegistry registry) {
        registry.add("sahanswitch.messaging.prefix", () -> PREFIX);
        registry.add("spring.rabbitmq.host", RabbitTestSupport::host);
        registry.add("spring.rabbitmq.port", RabbitTestSupport::port);
    }

    @Autowired
    private MessagingTopology topology;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @AfterAll
    static void removeQueues(
            @Autowired MessagingTopology topology,
            @Autowired RabbitAdmin admin,
            @Autowired RabbitListenerEndpointRegistry listeners
    ) {
        // stop the consumers first, otherwise they would re-create the queues we are deleting
        listeners.stop();
        admin.deleteQueue(topology.getRoutingQueue());
        admin.deleteQueue(topology.getRoutingDeadLetterQueue());
        admin.deleteQueue(topology.getStatusQueue());
        admin.deleteExchange(topology.getExchange());
    }

    // ================================================================ helpers

    private static void await(String what, BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for " + what);
            }
        }
        throw new AssertionError("Timed out waiting for " + what);
    }

    private String statusOf(String paymentId) {
        return get("/api/v1/payments/" + paymentId).json("$.status");
    }

    private String awaitSettled(String paymentId) {
        await("payment " + paymentId + " to settle", () -> {
            String status = statusOf(paymentId);
            return status.equals("COMPLETED") || status.equals("FAILED");
        });
        return statusOf(paymentId);
    }

    private Response submit(Participant sender, Participant destination, String account, String key) {
        return postJson("/api/v1/payments", jsonPayment(sender, destination, account, "75.00"),
                "Idempotency-Key", key, "X-Correlation-Id", "corr-" + key);
    }

    private int outboxRows(String paymentId, String status) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ?::uuid AND status = ?",
                Integer.class, paymentId, status);
    }

    // ================================================================ the flow

    @Test
    void paymentIsAcceptedImmediatelyThenCompletedByTheConsumer() {
        Participant sender = createParticipant("AS1");
        Participant destination = createParticipant("AD1");
        String key = "async-ok-" + UUID.randomUUID();

        Response created = submit(sender, destination, "ACC-DST-1", key);

        // the HTTP answer does not wait for the participant
        assertEquals(201, created.status(), created.body());
        assertEquals("ACCEPTED", created.json("$.status"));
        String paymentId = created.json("$.id");

        assertEquals("COMPLETED", awaitSettled(paymentId));
        assertNotNull(get("/api/v1/payments/" + paymentId).json("$.externalReference"));

        // audit: every step, in order, all carrying the id of the request that created the payment
        assertEquals(List.of("ACCEPTED", "PROCESSING", "COMPLETED"), auditStatuses(paymentId));
        Response audit = get("/api/v1/payments/" + paymentId + "/audit");
        for (int i = 0; i < 3; i++) {
            assertEquals("corr-" + key, audit.json("$[" + i + "].correlationId"));
        }
    }

    @Test
    void everyStatusChangeAndTheRoutingRequestEndUpPublishedInTheOutbox() {
        Participant sender = createParticipant("AS2");
        Participant destination = createParticipant("AD2");

        String paymentId = submit(sender, destination, "ACC-DST-1", "async-box-" + UUID.randomUUID()).json("$.id");
        awaitSettled(paymentId);

        // 1 routing request + ACCEPTED, PROCESSING, COMPLETED events
        await("the outbox to be relayed", () -> outboxRows(paymentId, "PUBLISHED") == 4);

        assertEquals(0, outboxRows(paymentId, "PENDING"));
        assertEquals(0, outboxRows(paymentId, "FAILED"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ?::uuid AND type = 'PaymentRoutingRequested'",
                Integer.class, paymentId));
        assertEquals(3, jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ?::uuid AND type = 'PaymentStatusChanged'",
                Integer.class, paymentId));
        assertNotNull(jdbc.queryForObject(
                "SELECT min(published_at) FROM outbox_events WHERE aggregate_id = ?::uuid", java.sql.Timestamp.class, paymentId));
    }

    @Test
    void aParticipantRejectionBecomesAFailedPaymentWithTheReason() {
        Participant sender = createParticipant("AS3");
        Participant destination = createParticipant("AD3");

        String paymentId = submit(sender, destination, "FAIL-1", "async-fail-" + UUID.randomUUID()).json("$.id");

        assertEquals("FAILED", awaitSettled(paymentId));
        assertTrue(((String) get("/api/v1/payments/" + paymentId).json("$.failureReason"))
                .contains("account cannot receive funds"));
        assertEquals(List.of("ACCEPTED", "PROCESSING", "FAILED"), auditStatuses(paymentId));
    }

    @Test
    void aParticipantTimeoutBecomesAFailedPayment() {
        Participant sender = createParticipant("AS4");
        Participant destination = createParticipant("AD4");

        String paymentId = submit(sender, destination, "TIMEOUT-1", "async-to-" + UUID.randomUUID()).json("$.id");

        assertEquals("FAILED", awaitSettled(paymentId));
        assertTrue(((String) get("/api/v1/payments/" + paymentId).json("$.failureReason")).contains("timeout"));
    }

    @Test
    void replayingTheSameRequestDoesNotRouteTheMoneyTwice() {
        Participant sender = createParticipant("AS5");
        Participant destination = createParticipant("AD5");
        String key = "async-replay-" + UUID.randomUUID();

        Response first = submit(sender, destination, "ACC-DST-1", key);
        String paymentId = first.json("$.id");
        Response replay = submit(sender, destination, "ACC-DST-1", key);

        assertEquals(200, replay.status(), "a replay is not a new payment");
        assertEquals(paymentId, replay.json("$.id"));

        awaitSettled(paymentId);
        await("the outbox to be relayed", () -> outboxRows(paymentId, "PUBLISHED") == 4);

        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ?::uuid AND type = 'PaymentRoutingRequested'",
                Integer.class, paymentId));
        assertEquals(3, auditStatuses(paymentId).size());
    }

    @Test
    void anIsoPaymentIsAcknowledgedThenSettledInTheBackground() {
        Participant sender = createParticipant("AS6");
        Participant destination = createParticipant("AD6");
        String e2e = "E2E" + UUID.randomUUID().toString().substring(0, 8);

        Response created = postXml("/api/v1/iso20022/pacs008",
                pacs008(sender, destination, UUID.randomUUID().toString(), e2e, "ACC-DST-1", "20.00"),
                sender.asCaller());

        assertEquals(201, created.status(), created.body());
        // accepted (ACCP), or already further along if the consumer was quicker than the report
        assertTrue(List.of("ACCP", "ACSP", "ACSC").contains(xmlValue(created.body(), "TxSts")), created.body());

        String paymentId = get("/api/v1/payments/search?senderParticipantId=" + sender.id()).json("$.content[0].id");
        assertEquals("COMPLETED", awaitSettled(paymentId));
        assertEquals(e2e, get("/api/v1/payments/" + paymentId).json("$.endToEndId"));

        // the stored status report now says "settled"
        assertEquals("ACSC", xmlValue(get("/api/v1/payments/" + paymentId + "/pacs002").body(), "TxSts"));
    }

    @Test
    void aMalformedRoutingMessageIsDeadLetteredNotRetriedForever() {
        Message poison = MessageBuilder.withBody("this is not json".getBytes(StandardCharsets.UTF_8)).build();

        rabbitTemplate.send(topology.getExchange(), MessagingTopology.ROUTING_KEY_ROUTING_REQUESTED, poison);

        await("the poison message to reach the dead-letter queue", () -> {
            Message dead = rabbitTemplate.receive(topology.getRoutingDeadLetterQueue());
            return dead != null && new String(dead.getBody(), StandardCharsets.UTF_8).equals("this is not json");
        });

        // and the real queue is empty again: the consumer is alive and not stuck on it
        await("the routing queue to drain", () ->
                rabbitAdmin.getQueueInfo(topology.getRoutingQueue()).getMessageCount() == 0);
    }

    @Test
    void aRequestForAPaymentThatDoesNotExistIsDiscardedWithoutBlockingTheQueue() {
        String body = "{\"paymentId\":\"" + UUID.randomUUID() + "\",\"destinationParticipantCode\":\"X\"}";
        rabbitTemplate.send(topology.getExchange(), MessagingTopology.ROUTING_KEY_ROUTING_REQUESTED,
                MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8)).build());

        await("the routing queue to drain", () ->
                rabbitAdmin.getQueueInfo(topology.getRoutingQueue()).getMessageCount() == 0);

        // a normal payment right after it still works
        Participant sender = createParticipant("AS7");
        Participant destination = createParticipant("AD7");
        String paymentId = submit(sender, destination, "ACC-DST-1", "after-ghost-" + UUID.randomUUID()).json("$.id");
        assertEquals("COMPLETED", awaitSettled(paymentId));
    }
}

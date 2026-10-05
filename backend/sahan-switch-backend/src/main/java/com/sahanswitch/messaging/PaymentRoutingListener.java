package com.sahanswitch.messaging;

import com.sahanswitch.common.web.CorrelationIdFilter;
import com.sahanswitch.payment.application.PaymentRoutingOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Task 2: consumes {@code payment-routing-requests} and runs the routing for each payment.
 *
 * <p>Only active in {@code async} routing mode. Error handling:
 * <ul>
 *   <li>a malformed message can never succeed, so it is rejected without requeue and goes straight
 *       to the dead-letter queue;</li>
 *   <li>a technical failure (database down, ...) throws; the listener's retry policy
 *       ({@code spring.rabbitmq.listener.simple.retry.*}) retries and finally dead-letters the
 *       message;</li>
 *   <li>a business outcome (participant said no, timed out) is NOT an error: the payment is settled
 *       as FAILED and the message is acknowledged.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "sahanswitch.routing", name = "mode", havingValue = "async", matchIfMissing = true)
public class PaymentRoutingListener {

    private static final Logger logger = LoggerFactory.getLogger(PaymentRoutingListener.class);

    private final PaymentRoutingOrchestrator orchestrator;
    private final ObjectMapper objectMapper;

    public PaymentRoutingListener(PaymentRoutingOrchestrator orchestrator, ObjectMapper objectMapper) {
        this.orchestrator = orchestrator;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = "#{messagingTopology.routingQueue}")
    public void onRoutingRequested(Message message) {

        UUID paymentId;
        String correlationId;

        try {
            JsonNode body = objectMapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
            paymentId = UUID.fromString(body.path("paymentId").asString());
            correlationId = body.path("correlationId").isMissingNode() || body.path("correlationId").isNull()
                    ? null
                    : body.path("correlationId").asString();
        } catch (RuntimeException exception) {
            logger.error("Discarding malformed routing request: {}", exception.getMessage());
            throw new AmqpRejectAndDontRequeueException("Malformed routing request", exception);
        }

        // Continue the correlation id of the HTTP request that accepted the payment, so the
        // audit rows written here share it with the ones written at acceptance time.
        if (correlationId != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        }

        try {
            logger.info("Routing request received for payment {}", paymentId);
            orchestrator.routePayment(paymentId);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}

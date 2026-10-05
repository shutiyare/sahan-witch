package com.sahanswitch.outbox;

import com.sahanswitch.messaging.MessagingTopology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Task 2: the outbox relay. A fixed-delay scheduled sweep that publishes PENDING outbox rows to
 * RabbitMQ.
 *
 * <p>One sweep, in one database transaction:
 * <ol>
 *   <li>claim the oldest PENDING rows with {@code FOR UPDATE SKIP LOCKED};</li>
 *   <li>publish each row and wait for the broker's <b>publisher confirm</b> (and check the
 *       message was routable to a queue);</li>
 *   <li>mark confirmed rows PUBLISHED; count a failed attempt on the others.</li>
 * </ol>
 *
 * <p>Failure handling:
 * <ul>
 *   <li><b>Broker unreachable</b>: the sweep stops and nothing is counted - an outage must not
 *       burn the retry budget of every pending event. Rows stay PENDING and flow again when the
 *       broker returns.</li>
 *   <li><b>Rejected / unroutable / no confirm in time</b>: the attempt is counted; after
 *       {@code maxAttempts} the row is parked as FAILED with the last error.</li>
 * </ul>
 *
 * <p>Guarantee: <b>at-least-once</b>. If the application dies after the broker confirmed but
 * before the row is marked PUBLISHED, the row is published again; consumers must be idempotent.
 */
@Component
@ConditionalOnProperty(prefix = "sahanswitch.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxProcessor {

    private static final Logger logger = LoggerFactory.getLogger(OutboxProcessor.class);

    private final OutboxEventRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final MessagingTopology topology;
    private final OutboxProperties properties;
    private final TransactionTemplate transactionTemplate;

    public OutboxProcessor(
            OutboxEventRepository repository,
            RabbitTemplate rabbitTemplate,
            MessagingTopology topology,
            OutboxProperties properties,
            TransactionTemplate transactionTemplate
    ) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.topology = topology;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * {@code fixedDelay}: the next sweep starts a fixed time after the previous one FINISHED, so
     * sweeps on one instance never overlap, however slow the broker is.
     */
    @Scheduled(
            fixedDelayString = "${sahanswitch.outbox.poll-interval-ms:1000}",
            initialDelayString = "${sahanswitch.outbox.poll-interval-ms:1000}"
    )
    public void sweep() {
        try {
            int published = processBatch();
            if (published > 0) {
                logger.debug("Outbox sweep published {} event(s)", published);
            }
        } catch (RuntimeException exception) {
            // Never let an exception escape: a scheduled task that throws is not skipped, but
            // an unexpected error must not hide in the scheduler's logs either.
            logger.error("Outbox sweep failed: {}", exception.getMessage(), exception);
        }
    }

    /** One sweep. Public so tests (and operators via a future endpoint) can trigger it directly. */
    public int processBatch() {

        Integer published = transactionTemplate.execute(status -> {

            List<OutboxEvent> batch = repository.lockPending(properties.batchSize());

            int count = 0;

            for (OutboxEvent event : batch) {
                try {
                    publish(event);
                    event.markPublished();
                    count++;

                } catch (PublishRejectedException exception) {
                    event.recordFailure(exception.getMessage(), properties.maxAttempts());
                    logger.warn("Outbox event {} ({}) was not published (attempt {}/{}): {}",
                            event.getId(), event.getType(), event.getAttempts(),
                            properties.maxAttempts(), exception.getMessage());

                } catch (AmqpException exception) {
                    // Broker down / connection lost: stop the sweep, leave everything PENDING
                    logger.warn("RabbitMQ unavailable, outbox sweep paused: {}", exception.getMessage());
                    break;
                }
            }

            return count;
        });

        return published == null ? 0 : published;
    }

    private void publish(OutboxEvent event) {

        MessagingTopology.Route route = topology.routeFor(event.getType())
                .orElseThrow(() -> new PublishRejectedException("No route configured for event type " + event.getType()));

        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        messageProperties.setContentEncoding(StandardCharsets.UTF_8.name());
        messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        messageProperties.setMessageId(event.getId().toString());
        messageProperties.setType(event.getType());
        messageProperties.setHeader("aggregateType", event.getAggregateType());
        messageProperties.setHeader("aggregateId", event.getAggregateId().toString());

        Message message = MessageBuilder
                .withBody(event.getPayload().getBytes(StandardCharsets.UTF_8))
                .andProperties(messageProperties)
                .build();

        CorrelationData correlation = new CorrelationData(event.getId().toString());

        rabbitTemplate.send(route.exchange(), route.routingKey(), message, correlation);

        awaitConfirmation(correlation);
    }

    /** Waits for the broker to acknowledge the message and checks it reached a queue. */
    private void awaitConfirmation(CorrelationData correlation) {
        try {
            CorrelationData.Confirm confirm =
                    correlation.getFuture().get(properties.confirmTimeoutMs(), TimeUnit.MILLISECONDS);

            if (!confirm.isAck()) {
                throw new PublishRejectedException("Broker negatively acknowledged the message: " + confirm.getReason());
            }

            if (correlation.getReturned() != null) {
                throw new PublishRejectedException("Message was unroutable: " + correlation.getReturned().getReplyText());
            }

        } catch (TimeoutException exception) {
            throw new PublishRejectedException("No publisher confirm within " + properties.confirmTimeoutMs() + " ms");

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PublishRejectedException("Interrupted while waiting for the publisher confirm");

        } catch (ExecutionException exception) {
            throw new PublishRejectedException("Publisher confirm failed: " + exception.getMessage());
        }
    }

    /** The broker (or our own routing table) refused the message: counts as a failed attempt. */
    static class PublishRejectedException extends RuntimeException {
        PublishRejectedException(String message) {
            super(message);
        }
    }
}

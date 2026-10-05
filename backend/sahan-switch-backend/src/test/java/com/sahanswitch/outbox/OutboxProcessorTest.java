package com.sahanswitch.outbox;

import com.sahanswitch.messaging.MessagingProperties;
import com.sahanswitch.messaging.MessagingTopology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxProcessorTest {

    private static final int MAX_ATTEMPTS = 3;

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);

    private OutboxProcessor processor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(null));

        processor = new OutboxProcessor(
                repository,
                rabbitTemplate,
                new MessagingTopology(new MessagingProperties("")),
                new OutboxProperties(true, 1000, 10, MAX_ATTEMPTS, 200),
                transactionTemplate
        );
    }

    private OutboxEvent routingEvent() {
        return withId(new OutboxEvent("PAYMENT", UUID.randomUUID(), OutboxEventTypes.PAYMENT_ROUTING_REQUESTED,
                "{\"paymentId\":\"p\"}"));
    }

    /** The database assigns the id on insert; rows read back by the processor always have one. */
    private static OutboxEvent withId(OutboxEvent event) {
        ReflectionTestUtils.setField(event, "id", UUID.randomUUID());
        return event;
    }

    /** Makes the mocked broker answer the publisher confirm the way the test needs. */
    private void brokerAnswers(Consumer<CorrelationData> answer) {
        doAnswer(invocation -> {
            answer.accept(invocation.getArgument(3));
            return null;
        }).when(rabbitTemplate).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
    }

    private static void ack(CorrelationData correlation) {
        correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
    }

    @Test
    void aConfirmedEventIsPublishedToTheRightRouteAndMarkedPublished() {
        OutboxEvent event = routingEvent();
        when(repository.lockPending(10)).thenReturn(List.of(event));
        brokerAnswers(OutboxProcessorTest::ack);

        int published = processor.processBatch();

        assertEquals(1, published);
        assertEquals(OutboxStatus.PUBLISHED, event.getStatus());
        assertNotNull(event.getPublishedAt());

        ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq("sahanswitch.payments"), eq("payment.routing.requested"),
                message.capture(), any(CorrelationData.class));
        assertEquals("{\"paymentId\":\"p\"}", new String(message.getValue().getBody(), StandardCharsets.UTF_8));
        MessageProperties properties = message.getValue().getMessageProperties();
        assertEquals(MessageProperties.CONTENT_TYPE_JSON, properties.getContentType());
        assertEquals(event.getAggregateId().toString(), properties.getHeader("aggregateId"));
    }

    @Test
    void aNegativeAcknowledgementCountsAnAttemptAndKeepsTheEventPending() {
        OutboxEvent event = routingEvent();
        when(repository.lockPending(10)).thenReturn(List.of(event));
        brokerAnswers(correlation -> correlation.getFuture().complete(new CorrelationData.Confirm(false, "disk full")));

        assertEquals(0, processor.processBatch());

        assertEquals(OutboxStatus.PENDING, event.getStatus());
        assertEquals(1, event.getAttempts());
        assertTrue(event.getLastError().contains("disk full"));
    }

    @Test
    void anUnroutableMessageIsNotTreatedAsDelivered() {
        OutboxEvent event = routingEvent();
        when(repository.lockPending(10)).thenReturn(List.of(event));
        brokerAnswers(correlation -> {
            correlation.setReturned(new ReturnedMessage(
                    new Message(new byte[0]), 312, "NO_ROUTE", "sahanswitch.payments", "payment.routing.requested"));
            ack(correlation); // the broker acks even unroutable messages; the return is what matters
        });

        assertEquals(0, processor.processBatch());

        assertEquals(OutboxStatus.PENDING, event.getStatus());
        assertEquals(1, event.getAttempts());
        assertTrue(event.getLastError().contains("unroutable"), event.getLastError());
    }

    @Test
    void noConfirmWithinTheTimeoutCountsAnAttempt() {
        OutboxEvent event = routingEvent();
        when(repository.lockPending(10)).thenReturn(List.of(event));
        brokerAnswers(correlation -> { /* never answers */ });

        assertEquals(0, processor.processBatch());

        assertEquals(1, event.getAttempts());
        assertTrue(event.getLastError().contains("No publisher confirm"), event.getLastError());
    }

    @Test
    void anEventIsParkedAsFailedAfterTheMaximumNumberOfAttempts() {
        OutboxEvent event = routingEvent();
        when(repository.lockPending(10)).thenReturn(List.of(event));
        brokerAnswers(correlation -> correlation.getFuture().complete(new CorrelationData.Confirm(false, "nope")));

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            processor.processBatch();
        }

        assertEquals(OutboxStatus.FAILED, event.getStatus());
        assertEquals(MAX_ATTEMPTS, event.getAttempts());
    }

    @Test
    void anUnknownEventTypeIsRejectedWithoutTouchingTheBroker() {
        OutboxEvent event = withId(new OutboxEvent("PAYMENT", UUID.randomUUID(), "Mystery", "{}"));
        when(repository.lockPending(10)).thenReturn(List.of(event));

        processor.processBatch();

        verify(rabbitTemplate, never()).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
        assertEquals(1, event.getAttempts());
        assertTrue(event.getLastError().contains("No route"));
    }

    @Test
    void whenTheBrokerIsDownTheSweepPausesWithoutBurningAnyAttempts() {
        OutboxEvent first = routingEvent();
        OutboxEvent second = routingEvent();
        when(repository.lockPending(10)).thenReturn(List.of(first, second));
        doThrow(new AmqpConnectException(new ConnectException("refused")))
                .when(rabbitTemplate).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));

        assertEquals(0, processor.processBatch());

        assertEquals(OutboxStatus.PENDING, first.getStatus());
        assertEquals(0, first.getAttempts(), "an outage must not consume the retry budget");
        assertEquals(0, second.getAttempts());
        assertNull(first.getLastError());
        verify(rabbitTemplate, times(1)).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
    }

    @Test
    void oneBadEventDoesNotBlockTheRestOfTheBatch() {
        OutboxEvent bad = withId(new OutboxEvent("PAYMENT", UUID.randomUUID(), "Mystery", "{}"));
        OutboxEvent good = routingEvent();
        when(repository.lockPending(10)).thenReturn(List.of(bad, good));
        brokerAnswers(OutboxProcessorTest::ack);

        assertEquals(1, processor.processBatch());

        assertEquals(OutboxStatus.PENDING, bad.getStatus());
        assertEquals(OutboxStatus.PUBLISHED, good.getStatus());
    }

    @Test
    void sweepNeverThrows() {
        when(repository.lockPending(anyInt())).thenThrow(new IllegalStateException("db gone"));

        processor.sweep(); // would fail the test if the exception escaped
    }
}

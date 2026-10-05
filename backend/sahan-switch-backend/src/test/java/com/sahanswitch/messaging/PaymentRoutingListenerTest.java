package com.sahanswitch.messaging;

import com.sahanswitch.common.web.CorrelationIdFilter;
import com.sahanswitch.payment.application.PaymentRoutingOrchestrator;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PaymentRoutingListenerTest {

    private final PaymentRoutingOrchestrator orchestrator = mock(PaymentRoutingOrchestrator.class);
    private final PaymentRoutingListener listener =
            new PaymentRoutingListener(orchestrator, JsonMapper.builder().build());

    private Message message(String body) {
        return MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8)).build();
    }

    @Test
    void aValidMessageRoutesThePaymentWithTheOriginalCorrelationId() {
        UUID paymentId = UUID.randomUUID();
        AtomicReference<String> seenCorrelation = new AtomicReference<>();
        doAnswer(invocation -> {
            seenCorrelation.set(MDC.get(CorrelationIdFilter.MDC_KEY));
            return null;
        }).when(orchestrator).routePayment(paymentId);

        listener.onRoutingRequested(message(
                "{\"paymentId\":\"" + paymentId + "\",\"destinationParticipantCode\":\"X\",\"correlationId\":\"corr-1\"}"));

        verify(orchestrator).routePayment(paymentId);
        assertEquals("corr-1", seenCorrelation.get(), "audit rows written by the consumer share the request's id");
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY), "the consumer thread is left clean");
    }

    @Test
    void aMessageWithoutACorrelationIdStillRoutes() {
        UUID paymentId = UUID.randomUUID();

        listener.onRoutingRequested(message("{\"paymentId\":\"" + paymentId + "\"}"));

        verify(orchestrator).routePayment(paymentId);
    }

    @Test
    void malformedMessagesAreRejectedWithoutRequeue() {
        for (String body : new String[]{"not json", "{}", "{\"paymentId\":\"not-a-uuid\"}", "[]"}) {
            assertThrows(AmqpRejectAndDontRequeueException.class,
                    () -> listener.onRoutingRequested(message(body)), body);
        }
        verify(orchestrator, never()).routePayment(any());
    }

    @Test
    void technicalFailuresPropagateSoTheRetryPolicyCanAct() {
        UUID paymentId = UUID.randomUUID();
        doThrow(new IllegalStateException("database down")).when(orchestrator).routePayment(paymentId);

        assertThrows(IllegalStateException.class,
                () -> listener.onRoutingRequested(message("{\"paymentId\":\"" + paymentId + "\"}")));
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }
}

package com.sahanswitch.messaging;

import com.sahanswitch.outbox.OutboxEventTypes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagingTopologyTest {

    @Test
    void defaultNamesMatchTheSpecification() {
        MessagingTopology topology = new MessagingTopology(new MessagingProperties(""));

        assertEquals("payment-routing-requests", topology.getRoutingQueue());
        assertEquals("payment-routing-requests.dlq", topology.getRoutingDeadLetterQueue());
        assertEquals("payment-status-events", topology.getStatusQueue());
        assertEquals("sahanswitch.payments", topology.getExchange());
    }

    @Test
    void thePrefixIsAppliedToEveryName() {
        MessagingTopology topology = new MessagingTopology(new MessagingProperties("t1."));

        assertEquals("t1.payment-routing-requests", topology.getRoutingQueue());
        assertEquals("t1.payment-routing-requests.dlq", topology.getRoutingDeadLetterQueue());
        assertEquals("t1.payment-status-events", topology.getStatusQueue());
        assertEquals("t1.sahanswitch.payments", topology.getExchange());
    }

    @Test
    void eventTypesMapToRoutingKeysAndUnknownTypesToNothing() {
        MessagingTopology topology = new MessagingTopology(new MessagingProperties(""));

        assertEquals("payment.routing.requested",
                topology.routeFor(OutboxEventTypes.PAYMENT_ROUTING_REQUESTED).orElseThrow().routingKey());
        assertEquals("payment.status.changed",
                topology.routeFor(OutboxEventTypes.PAYMENT_STATUS_CHANGED).orElseThrow().routingKey());
        assertTrue(topology.routeFor("SomethingElse").isEmpty());
    }
}

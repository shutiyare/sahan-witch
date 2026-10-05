package com.sahanswitch.messaging;

import com.sahanswitch.outbox.OutboxEventTypes;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Task 2: the single source of truth for broker names.
 *
 * <pre>
 *   exchange  sahanswitch.payments (topic)
 *     payment.routing.requested --> queue payment-routing-requests  (dead letters -> .dlq)
 *     payment.status.changed    --> queue payment-status-events
 * </pre>
 *
 * <p>Registered under the bean name {@code messagingTopology} so listener annotations can refer to
 * the queue name with SpEL ({@code "#{messagingTopology.routingQueue}"}), which keeps the
 * optional name prefix in one place.
 */
@Component("messagingTopology")
public class MessagingTopology {

    public static final String ROUTING_KEY_ROUTING_REQUESTED = "payment.routing.requested";
    public static final String ROUTING_KEY_STATUS_CHANGED = "payment.status.changed";

    /** Where an outbox event has to be published. */
    public record Route(String exchange, String routingKey) {
    }

    private final String exchange;
    private final String routingQueue;
    private final String routingDeadLetterQueue;
    private final String statusQueue;

    public MessagingTopology(MessagingProperties properties) {
        String prefix = properties.prefix();
        this.exchange = prefix + "sahanswitch.payments";
        this.routingQueue = prefix + "payment-routing-requests";
        this.routingDeadLetterQueue = routingQueue + ".dlq";
        this.statusQueue = prefix + "payment-status-events";
    }

    /** Maps an outbox event type to its destination; empty for an unknown type. */
    public Optional<Route> routeFor(String eventType) {
        return switch (eventType) {
            case OutboxEventTypes.PAYMENT_ROUTING_REQUESTED ->
                    Optional.of(new Route(exchange, ROUTING_KEY_ROUTING_REQUESTED));
            case OutboxEventTypes.PAYMENT_STATUS_CHANGED ->
                    Optional.of(new Route(exchange, ROUTING_KEY_STATUS_CHANGED));
            default -> Optional.empty();
        };
    }

    public String getExchange() {
        return exchange;
    }

    public String getRoutingQueue() {
        return routingQueue;
    }

    public String getRoutingDeadLetterQueue() {
        return routingDeadLetterQueue;
    }

    public String getStatusQueue() {
        return statusQueue;
    }
}

package com.sahanswitch.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Task 2: declares the RabbitMQ exchange, queues and bindings. Spring's {@code RabbitAdmin}
 * creates them on the broker the first time a connection opens (and re-creates them after a
 * broker restart), so no manual broker setup is needed.
 *
 * <p>Nothing here opens a connection at startup: the application boots even when the broker is
 * down; the relay simply keeps events PENDING until it is back.
 */
@Configuration
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingConfig {

    /** Status events are for optional subscribers; if nobody reads them they must not pile up. */
    private static final long STATUS_EVENT_TTL_MS = 24L * 60 * 60 * 1000;
    private static final long STATUS_EVENT_MAX_LENGTH = 100_000;

    @Bean
    public TopicExchange paymentsExchange(MessagingTopology topology) {
        return new TopicExchange(topology.getExchange(), true, false);
    }

    /**
     * Routing requests. A message that still fails after the listener's retries is rejected
     * without requeue and the broker moves it to the dead-letter queue below, so one poison
     * message can never block the queue.
     */
    @Bean
    public Queue routingRequestsQueue(MessagingTopology topology) {
        return QueueBuilder.durable(topology.getRoutingQueue())
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", topology.getRoutingDeadLetterQueue())
                .build();
    }

    @Bean
    public Queue routingRequestsDeadLetterQueue(MessagingTopology topology) {
        return QueueBuilder.durable(topology.getRoutingDeadLetterQueue()).build();
    }

    @Bean
    public Queue statusEventsQueue(MessagingTopology topology) {
        return QueueBuilder.durable(topology.getStatusQueue())
                .withArgument("x-message-ttl", STATUS_EVENT_TTL_MS)
                .withArgument("x-max-length", STATUS_EVENT_MAX_LENGTH)
                .build();
    }

    @Bean
    public Binding routingRequestsBinding(Queue routingRequestsQueue, TopicExchange paymentsExchange) {
        return BindingBuilder.bind(routingRequestsQueue)
                .to(paymentsExchange)
                .with(MessagingTopology.ROUTING_KEY_ROUTING_REQUESTED);
    }

    @Bean
    public Binding statusEventsBinding(Queue statusEventsQueue, TopicExchange paymentsExchange) {
        return BindingBuilder.bind(statusEventsQueue)
                .to(paymentsExchange)
                .with(MessagingTopology.ROUTING_KEY_STATUS_CHANGED);
    }
}

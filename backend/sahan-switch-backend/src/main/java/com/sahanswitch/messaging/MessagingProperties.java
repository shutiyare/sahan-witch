package com.sahanswitch.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Task 2: naming of the RabbitMQ objects ({@code sahanswitch.messaging.*}).
 *
 * @param prefix prepended to every exchange and queue name. Empty in normal use; integration
 *               tests set a unique prefix so they never touch (or consume from) real queues
 */
@ConfigurationProperties("sahanswitch.messaging")
public record MessagingProperties(
        @DefaultValue("") String prefix
) {
}

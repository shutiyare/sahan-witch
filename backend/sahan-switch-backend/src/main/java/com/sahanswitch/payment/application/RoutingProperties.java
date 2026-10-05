package com.sahanswitch.payment.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Task 2: {@code sahanswitch.routing.mode} = {@code async} (default) or {@code sync}.
 *
 * <p>{@code sync} keeps the original behaviour (final status in the HTTP response) and needs no
 * message broker, which is handy for local experiments and for the fast unit tests.
 */
@ConfigurationProperties("sahanswitch.routing")
public record RoutingProperties(
        @DefaultValue("async") RoutingMode mode
) {
}

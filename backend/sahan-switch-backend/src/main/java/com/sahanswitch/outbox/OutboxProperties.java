package com.sahanswitch.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Task 2: tuning for the outbox relay ({@code sahanswitch.outbox.*}).
 *
 * @param enabled         turns the relay and the recording of status events on/off. Must stay on in
 *                        asynchronous routing mode, because routing requests travel through the outbox
 * @param pollIntervalMs  pause between two sweeps (fixed DELAY: the next sweep starts this long
 *                        after the previous one finished, so sweeps never overlap)
 * @param batchSize       how many rows one sweep claims
 * @param maxAttempts     failed publish attempts before a row is parked as FAILED
 * @param confirmTimeoutMs how long to wait for the broker's publisher confirm
 */
@ConfigurationProperties("sahanswitch.outbox")
public record OutboxProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1000") long pollIntervalMs,
        @DefaultValue("50") int batchSize,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("5000") long confirmTimeoutMs
) {
}

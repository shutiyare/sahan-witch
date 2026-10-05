package com.sahanswitch.integration.infrastructure;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the Resilience4j objects used by {@code PaymentRoutingService}.
 *
 * <p><b>Task 5.3.</b> We use the plain Resilience4j libraries (no Spring Boot starter) and
 * configure them in code from {@link ResilienceProperties}:
 * <ul>
 *   <li>a {@link Retry}: up to {@code maxAttempts} tries with exponential backoff;</li>
 *   <li>a {@link CircuitBreakerRegistry}: one breaker <i>per participant</i> (created lazily
 *       by name), so one dead bank never blocks payments to the healthy ones.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(ResilienceProperties.class)
public class ResilienceConfig {

    @Bean
    public Retry participantRetry(ResilienceProperties properties) {

        ResilienceProperties.Retry retry = properties.retry();

        RetryConfig config = RetryConfig.custom()
                .maxAttempts(retry.maxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialBackoff(
                        retry.initialBackoff(),
                        retry.backoffMultiplier()
                ))
                // Retrying is pointless while the circuit is open: the next attempt would be
                // rejected immediately anyway. Every other failure is considered transient.
                .retryOnException(exception -> !(exception instanceof CallNotPermittedException))
                .build();

        return Retry.of("participant-routing", config);
    }

    @Bean
    public CircuitBreakerRegistry participantCircuitBreakerRegistry(ResilienceProperties properties) {

        ResilienceProperties.CircuitBreaker breaker = properties.circuitBreaker();

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(breaker.slidingWindowSize())
                .minimumNumberOfCalls(breaker.minimumNumberOfCalls())
                .failureRateThreshold(breaker.failureRateThreshold())
                .waitDurationInOpenState(breaker.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(breaker.permittedCallsInHalfOpen())
                .build();

        return CircuitBreakerRegistry.of(config);
    }
}

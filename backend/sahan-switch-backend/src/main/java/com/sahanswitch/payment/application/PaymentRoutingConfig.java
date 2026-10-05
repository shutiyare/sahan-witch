package com.sahanswitch.payment.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Task 2: registers {@link RoutingProperties}. */
@Configuration
@EnableConfigurationProperties(RoutingProperties.class)
public class PaymentRoutingConfig {
}

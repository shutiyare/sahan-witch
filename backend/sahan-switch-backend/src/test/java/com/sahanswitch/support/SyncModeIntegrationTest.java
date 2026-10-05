package com.sahanswitch.support;

import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An end-to-end test on a real PostgreSQL with <b>synchronous</b> routing and no message broker:
 * the final payment status is in the HTTP response, which keeps these tests fast and deterministic.
 *
 * <p>All tests carrying this annotation use identical settings, so Spring builds the application
 * once and reuses it. Retries wait only 1 ms and the circuit breaker is tightened (needs 3 calls)
 * so the failure paths run quickly. Asynchronous routing is covered by
 * {@code AsyncPaymentFlowIntegrationTest}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sahanswitch.routing.mode=sync",
                "sahanswitch.outbox.enabled=false",
                "sahanswitch.security.jwt.secret=" + ApiTestSupport.JWT_SECRET,
                "sahanswitch.security.admin.username=" + ApiTestSupport.ADMIN_USERNAME,
                "sahanswitch.security.admin.password=" + ApiTestSupport.ADMIN_PASSWORD,
                "sahanswitch.resilience.retry.initial-backoff=1ms",
                "sahanswitch.resilience.circuit-breaker.sliding-window-size=4",
                "sahanswitch.resilience.circuit-breaker.minimum-number-of-calls=3",
                "spring.jpa.show-sql=false"
        }
)
@EnabledIf(value = "com.sahanswitch.support.PostgresTestSupport#isAvailable",
        disabledReason = "Needs Docker (Testcontainers) or the TEST_DB_URL environment variable")
public @interface SyncModeIntegrationTest {
}

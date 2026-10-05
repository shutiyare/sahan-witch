package com.sahanswitch;

import com.sahanswitch.support.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Smoke test: the whole application context starts with the <b>default</b> configuration
 * (asynchronous routing, outbox on) and every Flyway migration (V1..V10) applies cleanly to an
 * empty PostgreSQL, with Hibernate's {@code ddl-auto=validate} confirming that the entities match
 * the resulting schema.
 *
 * <p>Uses a Testcontainers PostgreSQL (or {@code TEST_DB_URL}); skipped when neither is
 * available - see {@link PostgresTestSupport}.
 *
 * <p>The RabbitMQ listener is not started and the queue names carry a throw-away prefix, so this
 * test can never consume from, or leave objects on, a real broker.
 */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "sahanswitch.messaging.prefix=context-test.",
        // this context stays cached while other test classes run: it must never relay their outbox rows
        "sahanswitch.outbox.poll-interval-ms=3600000",
        "sahanswitch.security.jwt.secret=" + com.sahanswitch.support.ApiTestSupport.JWT_SECRET,
        // the first context to touch the database creates the admin; every test class must agree on its password
        "sahanswitch.security.admin.username=" + com.sahanswitch.support.ApiTestSupport.ADMIN_USERNAME,
        "sahanswitch.security.admin.password=" + com.sahanswitch.support.ApiTestSupport.ADMIN_PASSWORD
})
@EnabledIf(value = "com.sahanswitch.support.PostgresTestSupport#isAvailable",
        disabledReason = "Needs Docker (Testcontainers) or the TEST_DB_URL environment variable")
class SahanSwitchBackendApplicationTests {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresTestSupport.register(registry);
    }

    @Test
    void contextLoads() {
    }

}

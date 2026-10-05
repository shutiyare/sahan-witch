package com.sahanswitch.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Gives integration tests a real PostgreSQL database.
 *
 * <p><b>Task 6.2.</b> Two modes:
 * <ol>
 *   <li><b>Testcontainers (default).</b> A throw-away {@code postgres:16-alpine} container is
 *       started once per test JVM and shared by all integration tests. Needs Docker.</li>
 *   <li><b>External database.</b> If the environment variable {@code TEST_DB_URL} is set (e.g.
 *       {@code jdbc:postgresql://localhost:5432/sw_test}, optionally with {@code TEST_DB_USER}
 *       and {@code TEST_DB_PASSWORD}), that database is used instead. Handy on machines or CI
 *       runners without Docker. The database should be empty or disposable: Flyway migrates it
 *       and the tests insert data.</li>
 * </ol>
 * When neither is available, {@link #isAvailable()} is false and the integration tests are
 * skipped (instead of failing), so {@code ./mvnw test} still works on any machine.
 */
public final class PostgresTestSupport {

    private static final String IMAGE = "postgres:16-alpine";

    private static PostgreSQLContainer container;

    private PostgresTestSupport() {
    }

    /** Used by {@code @EnabledIf}: is there a database the integration tests can use? */
    public static boolean isAvailable() {
        if (externalUrl() != null) {
            return true;
        }
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable throwable) {
            return false;
        }
    }

    /** Points Spring's datasource at the test database. Call from a {@code @DynamicPropertySource} method. */
    public static void register(DynamicPropertyRegistry registry) {

        String external = externalUrl();

        if (external != null) {
            registry.add("spring.datasource.url", () -> external);
            registry.add("spring.datasource.username", () -> env("TEST_DB_USER", "postgres"));
            registry.add("spring.datasource.password", () -> env("TEST_DB_PASSWORD", ""));
            return;
        }

        PostgreSQLContainer postgres = startContainer();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    /** Started lazily and only once; the Testcontainers "Ryuk" sidecar removes it when the JVM exits. */
    private static synchronized PostgreSQLContainer startContainer() {
        if (container == null) {
            container = new PostgreSQLContainer(IMAGE);
            container.start();
        }
        return container;
    }

    private static String externalUrl() {
        String url = System.getenv("TEST_DB_URL");
        return (url == null || url.isBlank()) ? null : url;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }
}

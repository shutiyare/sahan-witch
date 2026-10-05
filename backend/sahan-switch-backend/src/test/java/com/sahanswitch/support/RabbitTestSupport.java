package com.sahanswitch.support;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Tells tests whether a RabbitMQ broker can be reached, so broker tests are skipped (not failed)
 * on machines without one. Defaults to {@code localhost:5672}; override with
 * {@code TEST_RABBIT_HOST} / {@code TEST_RABBIT_PORT}.
 */
public final class RabbitTestSupport {

    private RabbitTestSupport() {
    }

    public static String host() {
        return System.getenv().getOrDefault("TEST_RABBIT_HOST", "localhost");
    }

    public static int port() {
        return Integer.parseInt(System.getenv().getOrDefault("TEST_RABBIT_PORT", "5672"));
    }

    /** Used with {@code @EnabledIf("com.sahanswitch.support.RabbitTestSupport#isAvailable")}. */
    public static boolean isAvailable() {
        if (!PostgresTestSupport.isAvailable()) {
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host(), port()), 500);
            return true;
        } catch (Exception exception) {
            return false;
        }
    }
}

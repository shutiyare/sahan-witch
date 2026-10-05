package com.sahanswitch.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Task 1: externalized security settings ({@code sahanswitch.security.*}).
 *
 * <p>Nothing secret has a usable default here: the JWT secret and the first admin password
 * come from the environment ({@code application-dev.yml} supplies development-only values).
 *
 * @param jwt   token signing settings
 * @param admin first administrator created on startup when no ADMIN exists yet
 * @param cors  browser origins allowed to call the API (the Vite dev server by default)
 */
@ConfigurationProperties("sahanswitch.security")
public record SecurityProperties(
        @DefaultValue Jwt jwt,
        @DefaultValue Admin admin,
        @DefaultValue Cors cors
) {

    /**
     * @param secret HMAC key for HS256; at least 32 bytes, checked at startup
     * @param ttl    how long an issued token stays valid
     * @param issuer value of the {@code iss} claim
     */
    public record Jwt(
            @DefaultValue("") String secret,
            @DefaultValue("1h") Duration ttl,
            @DefaultValue("sahan-switch") String issuer
    ) {
    }

    /**
     * @param username name of the bootstrap administrator
     * @param password its password; when blank a random one is generated and logged once
     */
    public record Admin(
            @DefaultValue("admin") String username,
            @DefaultValue("") String password
    ) {
    }

    public record Cors(
            @DefaultValue({"http://localhost:5173", "http://127.0.0.1:5173"}) List<String> allowedOrigins
    ) {
    }
}

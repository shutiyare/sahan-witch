package com.sahanswitch.security;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Task 1: creates and hashes participant API keys.
 *
 * <p>A key looks like {@code ssk_<43 url-safe characters>} and carries 256 bits of randomness.
 * Because the key itself is that random, a plain SHA-256 is enough to protect it at rest, and a
 * <em>deterministic</em> hash lets authentication find the participant with one indexed
 * lookup (a salted hash such as BCrypt could not be searched). Only the hash is stored.
 */
@Component
public class ApiKeyService {

    public static final String PREFIX = "ssk_";

    private final SecureRandom secureRandom = new SecureRandom();

    /** A new random key in clear text. Show it to the user once and never store it. */
    public String generateKey() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Lower-case hex SHA-256 of the key: what is stored in {@code participants.api_key_hash}. */
    public String hash(String apiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(apiKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            // Every Java platform is required to provide SHA-256
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}

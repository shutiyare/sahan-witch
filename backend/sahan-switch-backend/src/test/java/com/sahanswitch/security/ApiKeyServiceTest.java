package com.sahanswitch.security;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyServiceTest {

    private final ApiKeyService service = new ApiKeyService();

    @Test
    void generatedKeysHaveThePrefixAndAreLongUrlSafeStrings() {
        String key = service.generateKey();

        assertTrue(key.startsWith("ssk_"));
        assertEquals(4 + 43, key.length(), "prefix + 256 bits in unpadded base64url");
        assertTrue(key.substring(4).matches("[A-Za-z0-9_-]+"), key);
    }

    @Test
    void generatedKeysDoNotRepeat() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            keys.add(service.generateKey());
        }
        assertEquals(1000, keys.size());
    }

    @Test
    void hashIsDeterministicLowerCaseSha256Hex() {
        // well-known SHA-256 test vector
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", service.hash("abc"));
        assertEquals(service.hash("same"), service.hash("same"));
    }

    @Test
    void differentKeysHaveDifferentHashesAndTheHashIsNotTheKey() {
        String key = service.generateKey();

        assertNotEquals(service.hash(key), service.hash(service.generateKey()));
        assertNotEquals(key, service.hash(key));
        assertEquals(64, service.hash(key).length());
    }
}

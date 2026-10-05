package com.sahanswitch.security;

import com.sahanswitch.support.ApiTestSupport;
import com.sahanswitch.support.SyncModeIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 1, end to end over real HTTP: who can sign in, who can call what, and - most important
 * for a payment switch - that one participant can never act as, or look at, another.
 */
@SyncModeIntegrationTest
class SecurityIntegrationTest extends ApiTestSupport {

    // ================================================================ login

    @Test
    void loginReturnsASignedTokenAndTheTokenOpensTheApi() {
        Response login = login(ADMIN_USERNAME, ADMIN_PASSWORD);

        assertEquals(200, login.status(), login.body());
        assertEquals("Bearer", login.json("$.tokenType"));
        assertTrue(((Number) login.json("$.expiresIn")).longValue() > 0);
        assertEquals("ADMIN", login.json("$.role"));

        String token = login.json("$.accessToken");
        assertEquals(3, token.split("\\.").length, "a JWT has three dot-separated parts");

        assertEquals(200, get("/api/v1/participants", "Authorization", bearer(token)).status());
    }

    @Test
    void wrongPasswordAndUnknownUserAreIndistinguishable() {
        Response wrongPassword = login(ADMIN_USERNAME, "not-the-password");
        Response unknownUser = login("nobody-" + UUID.randomUUID(), "whatever");

        assertEquals(401, wrongPassword.status());
        assertEquals(401, unknownUser.status());
        assertEquals((String) wrongPassword.json("$.message"), unknownUser.json("$.message"),
                "the message must not reveal which usernames exist");
        assertFalse(wrongPassword.body().contains("accessToken"));
    }

    @Test
    void loginValidatesItsInput() {
        Response response = postJson("/api/v1/auth/login", "{\"username\":\"\",\"password\":\"\"}", ANONYMOUS, "true");

        assertEquals(400, response.status(), response.body());
    }

    // ================================================================ unauthenticated access

    @Test
    void protectedEndpointsRejectRequestsWithoutCredentials() {
        Response response = get("/api/v1/participants", ANONYMOUS, "true");

        assertEquals(401, response.status());
        assertEquals("Unauthorized", response.json("$.error"));
        assertNotNull(response.json("$.timestamp"));
    }

    @Test
    void publicEndpointsStayOpen() {
        assertEquals(200, get("/actuator/health", ANONYMOUS, "true").status());
    }

    @Test
    void operationalActuatorEndpointsAreAdminOnly() {
        Participant participant = createParticipant("ACT");

        assertEquals(401, get("/actuator/metrics", ANONYMOUS, "true").status());
        assertEquals(403, get("/actuator/metrics", participant.asCaller()).status());
    }

    // ================================================================ JWT validation

    @Test
    void aTamperedTokenIsRejected() {
        String token = adminToken();
        String tampered = token.substring(0, token.length() - 3)
                + (token.endsWith("AAA") ? "BBB" : "AAA");

        assertNotEquals(token, tampered);
        assertEquals(401, get("/api/v1/participants", "Authorization", bearer(tampered)).status());
    }

    @Test
    void garbageInTheAuthorizationHeaderIsRejected() {
        assertEquals(401, get("/api/v1/participants", "Authorization", "Bearer not.a.jwt").status());
    }

    // ================================================================ API keys

    @Test
    void anApiKeyIsShownOnceAndOnlyItsHashIsStored() {
        Participant participant = createParticipant("KEY");

        assertTrue(participant.apiKey().startsWith("ssk_"));

        // it is not returned again ...
        Response read = get("/api/v1/participants/" + participant.id());
        assertEquals(200, read.status());
        assertFalse(read.body().contains(participant.apiKey()), "clear-text key must never be readable again");
        assertEquals(true, read.json("$.hasApiKey"));

        // ... and the database holds the hash, not the key
        String stored = jdbc.queryForObject(
                "SELECT api_key_hash FROM participants WHERE id = ?::uuid", String.class, participant.id());
        assertNotEquals(participant.apiKey(), stored);
        assertEquals(64, stored.length(), "SHA-256 hex");
    }

    @Test
    void anApiKeyAuthenticatesAsItsParticipant() {
        Participant participant = createParticipant("OWN");

        assertEquals(200, get("/api/v1/participants/" + participant.id(), participant.asCaller()).status());
    }

    @Test
    void anUnknownApiKeyIsRejected() {
        Response response = get("/api/v1/participants", "X-API-KEY", "ssk_" + "x".repeat(43));

        assertEquals(401, response.status());
    }

    @Test
    void rotatingAKeyInvalidatesTheOldOne() {
        Participant participant = createParticipant("ROT");

        Response rotated = postJson("/api/v1/participants/" + participant.id() + "/api-key", null);
        assertEquals(200, rotated.status(), rotated.body());
        String newKey = rotated.json("$.apiKey");
        assertNotEquals(participant.apiKey(), newKey);

        assertEquals(401, get("/api/v1/participants", participant.asCaller()).status(), "old key is dead");
        assertEquals(200, get("/api/v1/participants", "X-API-KEY", newKey).status(), "new key works");
    }

    @Test
    void aDeactivatedParticipantsKeyStopsWorking() {
        Participant participant = createParticipant("OFF");
        assertEquals(200, get("/api/v1/participants", participant.asCaller()).status());

        assertEquals(200, patch("/api/v1/participants/" + participant.id() + "/deactivate").status());

        assertEquals(401, get("/api/v1/participants", participant.asCaller()).status());
    }

    // ================================================================ role rules

    @Test
    void aParticipantCannotAdministerTheSwitch() {
        Participant participant = createParticipant("ADM");
        Participant other = createParticipant("OTH");

        assertEquals(403, postJson("/api/v1/participants",
                "{\"code\":\"EVIL" + UUID.randomUUID().toString().substring(0, 6).toUpperCase()
                        + "\",\"name\":\"Evil\",\"type\":\"BANK\"}", participant.asCaller()).status());

        assertEquals(403, patch("/api/v1/participants/" + other.id() + "/deactivate", participant.asCaller()).status());
        assertEquals(403, postJson("/api/v1/participants/" + other.id() + "/api-key", null,
                participant.asCaller()).status());
        assertEquals(403, get("/api/v1/analytics/summary", participant.asCaller()).status());
    }

    @Test
    void aForbiddenResponseHasTheStandardErrorShape() {
        Participant participant = createParticipant("SHP");

        Response response = get("/api/v1/analytics/summary", participant.asCaller());

        assertEquals(403, response.status());
        assertEquals("Forbidden", response.json("$.error"));
        assertEquals(403, ((Number) response.json("$.status")).intValue());
    }

    // ================================================================ tenant isolation

    @Test
    void aParticipantCanOnlyInitiatePaymentsAsItself() {
        Participant alice = createParticipant("ALI");
        Participant bob = createParticipant("BOB");

        // Alice sends as Alice: allowed
        Response own = postJson("/api/v1/payments", jsonPayment(alice, bob, "ACC-DST-1", "10.00"),
                "Idempotency-Key", "own-" + UUID.randomUUID(), "X-API-KEY", alice.apiKey());
        assertEquals(201, own.status(), own.body());
        assertEquals(alice.id(), own.json("$.senderParticipantId"));

        // Alice tries to send as Bob: refused, and nothing is stored
        Response spoof = postJson("/api/v1/payments", jsonPayment(bob, alice, "ACC-DST-1", "10.00"),
                "Idempotency-Key", "spoof-" + UUID.randomUUID(), "X-API-KEY", alice.apiKey());
        assertEquals(403, spoof.status(), spoof.body());
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM payments WHERE participant_id = ?::uuid", Integer.class, bob.id()));
    }

    @Test
    void aParticipantCannotSubmitAnIsoMessageAsSomeoneElse() {
        Participant alice = createParticipant("ISA");
        Participant bob = createParticipant("ISB");
        String e2e = "E2E" + UUID.randomUUID().toString().substring(0, 8);

        // the message names Bob as the debtor agent, but Alice is the caller
        Response response = postXml("/api/v1/iso20022/pacs008",
                pacs008(bob, alice, UUID.randomUUID().toString(), e2e, "ACC-DST-1", "5.00"),
                alice.asCaller());

        assertEquals(403, response.status(), response.body());
    }

    @Test
    void aParticipantCannotReadPaymentsItIsNotPartyTo() {
        Participant alice = createParticipant("RDA");
        Participant bob = createParticipant("RDB");
        Participant carol = createParticipant("RDC");

        Response created = postJson("/api/v1/payments", jsonPayment(alice, bob, "ACC-DST-1", "10.00"),
                "Idempotency-Key", "read-" + UUID.randomUUID(), "X-API-KEY", alice.apiKey());
        assertEquals(201, created.status(), created.body());
        String paymentId = created.json("$.id");
        String reference = created.json("$.paymentReference");

        // both parties may read it, in every representation
        for (Participant party : new Participant[]{alice, bob}) {
            assertEquals(200, get("/api/v1/payments/" + paymentId, party.asCaller()).status());
            assertEquals(200, get("/api/v1/payments/by-reference/" + reference, party.asCaller()).status());
            assertEquals(200, get("/api/v1/payments/" + paymentId + "/audit", party.asCaller()).status());
            assertEquals(200, get("/api/v1/payments/" + paymentId + "/pacs008", party.asCaller()).status());
            assertEquals(200, get("/api/v1/payments/" + paymentId + "/pacs002", party.asCaller()).status());
        }

        // a third participant sees "not found", exactly as for an id that does not exist
        Response random = get("/api/v1/payments/" + UUID.randomUUID(), carol.asCaller());
        for (String path : new String[]{
                "/api/v1/payments/" + paymentId,
                "/api/v1/payments/by-reference/" + reference,
                "/api/v1/payments/" + paymentId + "/audit",
                "/api/v1/payments/" + paymentId + "/pacs008",
                "/api/v1/payments/" + paymentId + "/pacs002"}) {
            Response denied = get(path, carol.asCaller());
            assertEquals(404, denied.status(), path + " -> " + denied.body());
        }
        assertEquals(404, random.status());
    }

    @Test
    void aParticipantCannotSettlePaymentsByHand() {
        Participant alice = createParticipant("MNA");
        Participant bob = createParticipant("MNB");

        Response created = postJson("/api/v1/payments", jsonPayment(alice, bob, "FAIL-1", "10.00"),
                "Idempotency-Key", "man-" + UUID.randomUUID(), "X-API-KEY", alice.apiKey());
        String paymentId = created.json("$.id");

        assertEquals(403, postJson("/api/v1/payments/" + paymentId + "/complete", null, alice.asCaller()).status());
        assertEquals(403, postJson("/api/v1/payments/" + paymentId + "/fail", null, bob.asCaller()).status());
    }

    @Test
    void searchOnlyReturnsThePaymentsOfTheCaller() {
        Participant alice = createParticipant("SRA");
        Participant bob = createParticipant("SRB");
        Participant carol = createParticipant("SRC");

        for (int i = 0; i < 2; i++) {
            assertEquals(201, postJson("/api/v1/payments", jsonPayment(alice, bob, "ACC-DST-1", "10.00"),
                    "Idempotency-Key", "s1-" + UUID.randomUUID(), "X-API-KEY", alice.apiKey()).status());
        }
        assertEquals(201, postJson("/api/v1/payments", jsonPayment(carol, bob, "ACC-DST-1", "10.00"),
                "Idempotency-Key", "s2-" + UUID.randomUUID(), "X-API-KEY", carol.apiKey()).status());

        // Bob received three payments, Alice sent two
        assertEquals(3, ((Number) get("/api/v1/payments/search", bob.asCaller())
                .json("$.page.totalElements")).intValue());
        assertEquals(2, ((Number) get("/api/v1/payments/search", alice.asCaller())
                .json("$.page.totalElements")).intValue());

        // asking for somebody else's payments still only returns the caller's own
        Response snoop = get("/api/v1/payments/search?senderParticipantId=" + carol.id(), alice.asCaller());
        assertEquals(0, ((Number) snoop.json("$.page.totalElements")).intValue());
    }
}

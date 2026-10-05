package com.sahanswitch.support;

import com.jayway.jsonpath.JsonPath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared helpers for integration tests that call the running application over real HTTP.
 *
 * <ul>
 *   <li>Every request is sent as the <b>administrator</b> (a JWT obtained through the real login
 *       endpoint) unless the test passes its own {@code Authorization} / {@code X-API-KEY}
 *       header, or {@link #ANONYMOUS}.</li>
 *   <li>Participants are created through the API; the response contains the participant's API key,
 *       so tests can also call the API <em>as</em> that participant.</li>
 * </ul>
 */
public abstract class ApiTestSupport {

    public static final String ADMIN_USERNAME = "admin";
    public static final String ADMIN_PASSWORD = "Test-Admin-Pass-123";
    public static final String JWT_SECRET = "integration-test-secret-0123456789-abcdefghij";

    /** Pass as a header name to send a request with no credentials at all. */
    public static final String ANONYMOUS = "X-Test-Anonymous";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresTestSupport.register(registry);
    }

    @Value("${local.server.port}")
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;

    private RestClient client;
    private String adminToken;

    protected RestClient client() {
        if (client == null) {
            client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        }
        return client;
    }

    // ================================================================ HTTP helpers

    public record Response(int status, String body, HttpHeaders headers) {

        public <T> T json(String path) {
            return JsonPath.read(body, path);
        }
    }

    protected Response send(HttpMethod method, String path, MediaType contentType, String body, String... headerPairs) {

        boolean hasCredentials = false;
        boolean anonymous = false;

        for (int i = 0; i < headerPairs.length; i += 2) {
            String name = headerPairs[i];
            if (name.equalsIgnoreCase("Authorization") || name.equalsIgnoreCase("X-API-KEY")) {
                hasCredentials = true;
            }
            if (name.equals(ANONYMOUS)) {
                anonymous = true;
            }
        }

        final boolean useAdmin = !hasCredentials && !anonymous;

        RestClient.RequestBodySpec request = client().method(method).uri(path);

        request.headers(headers -> {
            for (int i = 0; i < headerPairs.length; i += 2) {
                if (!headerPairs[i].equals(ANONYMOUS)) {
                    headers.set(headerPairs[i], headerPairs[i + 1]);
                }
            }
            if (useAdmin) {
                headers.setBearerAuth(adminToken());
            }
        });

        if (body != null) {
            request.contentType(contentType).body(body);
        }

        return request.exchange((req, res) ->
                new Response(res.getStatusCode().value(),
                        new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8),
                        res.getHeaders()));
    }

    protected Response get(String path, String... headerPairs) {
        return send(HttpMethod.GET, path, null, null, headerPairs);
    }

    protected Response postJson(String path, String json, String... headerPairs) {
        return send(HttpMethod.POST, path, MediaType.APPLICATION_JSON, json, headerPairs);
    }

    protected Response postXml(String path, String xml, String... headerPairs) {
        return send(HttpMethod.POST, path, MediaType.APPLICATION_XML, xml, headerPairs);
    }

    protected Response patch(String path, String... headerPairs) {
        return send(HttpMethod.PATCH, path, null, null, headerPairs);
    }

    // ================================================================ authentication helpers

    /** Signs in as the bootstrap administrator through the real login endpoint (once per test instance). */
    protected String adminToken() {
        if (adminToken == null) {
            adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD).json("$.accessToken");
        }
        return adminToken;
    }

    protected Response login(String username, String password) {
        return postJson("/api/v1/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}", ANONYMOUS, "true");
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }

    // ================================================================ test data helpers

    public record Participant(String id, String code, String apiKey) {

        /** Header pair that authenticates a request as this participant. */
        public String[] asCaller() {
            return new String[]{"X-API-KEY", apiKey};
        }
    }

    /** Creates a participant through the API. Codes are unique per call, so tests never collide. */
    protected Participant createParticipant(String prefix) {
        String code = (prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10)).toUpperCase();

        Response response = postJson("/api/v1/participants",
                "{\"code\":\"" + code + "\",\"name\":\"" + prefix + " participant\",\"type\":\"BANK\"}");

        assertEquals(201, response.status(), response.body());
        return new Participant(response.json("$.id"), code, response.json("$.apiKey"));
    }

    protected String pacs008(Participant sender, Participant destination, String uetr, String e2e,
                             String destinationAccount, String amount) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pacs.008.001.10\"><FIToFICstmrCdtTrf>"
                + "<GrpHdr><MsgId>MSG-" + e2e + "</MsgId><CreDtTm>2026-10-05T07:00:00.000Z</CreDtTm>"
                + "<NbOfTxs>1</NbOfTxs><SttlmInf><SttlmMtd>CLRG</SttlmMtd></SttlmInf></GrpHdr>"
                + "<CdtTrfTxInf>"
                + "<PmtId><InstrId>I-" + e2e + "</InstrId><EndToEndId>" + e2e + "</EndToEndId>"
                + (uetr == null ? "" : "<UETR>" + uetr + "</UETR>") + "</PmtId>"
                + "<IntrBkSttlmAmt Ccy=\"USD\">" + amount + "</IntrBkSttlmAmt><ChrgBr>SLEV</ChrgBr>"
                + "<Dbtr><Nm>Alice Debtor</Nm></Dbtr>"
                + "<DbtrAcct><Id><Othr><Id>ACC-SRC-1</Id></Othr></Id></DbtrAcct>"
                + "<DbtrAgt><FinInstnId><ClrSysMmbId><MmbId>" + sender.code() + "</MmbId></ClrSysMmbId></FinInstnId></DbtrAgt>"
                + "<CdtrAgt><FinInstnId><ClrSysMmbId><MmbId>" + destination.code() + "</MmbId></ClrSysMmbId></FinInstnId></CdtrAgt>"
                + "<Cdtr><Nm>Bob Creditor</Nm></Cdtr>"
                + "<CdtrAcct><Id><Othr><Id>" + destinationAccount + "</Id></Othr></Id></CdtrAcct>"
                + "</CdtTrfTxInf></FIToFICstmrCdtTrf></Document>";
    }

    protected String jsonPayment(Participant sender, Participant destination, String destinationAccount, String amount) {
        return jsonPayment(sender, destination, destinationAccount, amount, "USD");
    }

    protected String jsonPayment(Participant sender, Participant destination, String destinationAccount,
                                 String amount, String currency) {
        return "{\"senderParticipantId\":\"" + sender.id() + "\",\"destinationParticipantId\":\"" + destination.id()
                + "\",\"sourceAccount\":\"ACC-SRC-1\",\"destinationAccount\":\"" + destinationAccount
                + "\",\"amount\":" + amount + ",\"currency\":\"" + currency + "\"}";
    }

    protected String xmlValue(String xml, String element) {
        Matcher matcher = Pattern.compile("<" + element + ">(.*?)</" + element + ">").matcher(xml);
        assertTrue(matcher.find(), element + " not found in " + xml);
        return matcher.group(1);
    }

    protected List<String> auditStatuses(String paymentId) {
        Response audit = get("/api/v1/payments/" + paymentId + "/audit");
        assertEquals(200, audit.status(), audit.body());
        List<String> statuses = new ArrayList<>();
        int size = ((List<?>) audit.json("$")).size();
        for (int i = 0; i < size; i++) {
            statuses.add(audit.json("$[" + i + "].newStatus"));
        }
        return statuses;
    }
}

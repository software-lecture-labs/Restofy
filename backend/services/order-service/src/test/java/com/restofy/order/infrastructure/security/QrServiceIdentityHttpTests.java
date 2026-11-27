package com.restofy.order.infrastructure.security;

import com.restofy.order.application.port.ServiceIdentityPort;
import com.restofy.order.infrastructure.client.mock.InMemoryServiceIdentityAdapter;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ActiveProfiles("order-qr")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=classpath:application-test.properties",
        "spring.datasource.url=jdbc:tc:postgresql:17-alpine:///restofy_qr_identity"
})
class QrServiceIdentityHttpTests {

    private static final KeyPair KEY = Jwts.SIG.RS256.keyPair().build();
    private static final KeyPair OTHER_KEY = Jwts.SIG.RS256.keyPair().build();
    private static final UUID RESTAURANT = UUID.randomUUID();
    private static final UUID OTHER_RESTAURANT = UUID.randomUUID();
    private static final String DIGEST = "d".repeat(64);
    private static final String AUDIENCE = "restofy-order";
    private static final String SUBJECT = "qr-service";
    private static final String SCOPE = "table-session:lifecycle";
    private static final String SERVICE = "Service ";

    @LocalServerPort
    private int port;
    @Autowired
    private ServiceIdentityPort verifier;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JsonMapper json;

    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void trust(DynamicPropertyRegistry props) {
        props.add("ORDER_QR_SERVICE_PUBLIC_KEYS", QrServiceIdentityHttpTests::configuredKeys);
        props.add("ORDER_QR_SERVICE_RESTAURANTS", RESTAURANT::toString);
        props.add("ORDER_QR_CALLER_KEY_ID", () -> "local-test");
        props.add("ORDER_QR_CALLER_PRIVATE_KEY", QrServiceIdentityHttpTests::outboundKey);
        props.add("ORDER_QR_CALLER_RESTAURANT_ID", RESTAURANT::toString);
        props.add("QR_SERVICE_URL", () -> "https://localhost");
        props.add("CORS_ALLOWED_ORIGINS", () -> "https://guest.example");
    }

    private static String outboundKey() {
        return Base64.getEncoder().encodeToString(OTHER_KEY.getPrivate().getEncoded());
    }

    private static String configuredKeys() {
        return "{\"current\":\"" + Base64.getEncoder().encodeToString(KEY.getPublic().getEncoded()) + "\"}";
    }

    @Test
    void realProfileRequiresValidConfigurationAndRejectsDevOrTest() {
        assertInstanceOf(QrServiceIdentityVerifier.class, verifier);
        var environment = new MockEnvironment();
        var clock = Clock.systemUTC();
        String restaurant = RESTAURANT.toString();
        String keys = configuredKeys();
        assertThrows(IllegalStateException.class,
                () -> new QrServiceIdentityVerifier(clock, environment, json, "", restaurant));
        assertThrows(IllegalStateException.class,
                () -> new QrServiceIdentityVerifier(clock, environment, json, "{\"current\":\"broken\"}", restaurant));
        assertThrows(IllegalStateException.class,
                () -> new QrServiceIdentityVerifier(clock, environment, json, keys, ""));
        environment.setActiveProfiles("order-qr", "dev");
        assertThrows(IllegalStateException.class,
                () -> new QrServiceIdentityVerifier(clock, environment, json, keys, restaurant));
    }

    @Test
    void onlyValidShortLivedQrCredentialsAuthenticate() throws Exception {
        String path = stateTarget(UUID.randomUUID(), RESTAURANT);
        check(call("GET", path, "", null), 401, "UNAUTHENTICATED");
        check(call("GET", path, "", SERVICE + InMemoryServiceIdentityAdapter.QR_CREDENTIAL), 401, "UNAUTHENTICATED");
        check(call("GET", path, "", token("GET", path, "", RESTAURANT, SUBJECT, AUDIENCE, SCOPE, OTHER_KEY, now())),
                401, "UNAUTHENTICATED");
        check(call("GET", path, "", token("GET", path, "", RESTAURANT, SUBJECT, "restofy-payment", SCOPE, KEY, now())),
                401, "UNAUTHENTICATED");
        check(call("GET", path, "", token("GET", path, "", RESTAURANT, SUBJECT, AUDIENCE, SCOPE, KEY, now().minusSeconds(31))),
                401, "UNAUTHENTICATED");
        check(call("GET", path, "", token("GET", path, "", RESTAURANT, "kitchen-service", AUDIENCE, SCOPE, KEY, now())),
                403, "ACCESS_DENIED");
        check(call("GET", path, "", token("GET", path, "", RESTAURANT, SUBJECT, AUDIENCE, "order:events", KEY, now())),
                403, "ACCESS_DENIED");
        assertEquals("NO_ORDERS", json.readTree(call("GET", path, "", signed("GET", path, "")).body()).path("knowledge").asString());
    }

    @Test
    void signedTenantPathAndBodyMustMatchBeforeOrderUsesThem() throws Exception {
        String otherTenant = stateTarget(UUID.randomUUID(), OTHER_RESTAURANT);
        check(call("GET", otherTenant, "", signed("GET", otherTenant, "")), 403, "ACCESS_DENIED");
        check(call("GET", otherTenant, "", token("GET", otherTenant, "", OTHER_RESTAURANT, SUBJECT, AUDIENCE, SCOPE, KEY, now())),
                403, "ACCESS_DENIED");
        String resource = preparations(UUID.randomUUID());
        String status = resource + "/" + UUID.randomUUID() + "?restaurantId=" + RESTAURANT + "&commandDigest=" + DIGEST;
        String differentOperation = resource + "/" + UUID.randomUUID() + "?restaurantId=" + RESTAURANT + "&commandDigest=" + DIGEST;
        check(call("GET", differentOperation, "", signed("GET", status, "")), 403, "ACCESS_DENIED");
        String body = idleBody(UUID.randomUUID());
        check(call("POST", resource, body + " ", signed("POST", resource, body)), 403, "ACCESS_DENIED");
    }

    @Test
    void signedIdleFlowUsesDurablePrepareStatusAndConfirmation() throws Exception {
        String resource = preparations(UUID.randomUUID());
        UUID operation = UUID.randomUUID();
        String status = resource + "/" + operation + "?restaurantId=" + RESTAURANT + "&commandDigest=" + DIGEST;
        var initial = call("GET", status, "", signed("GET", status, ""));
        assertEquals(200, initial.statusCode());
        var before = json.readTree(initial.body());
        assertEquals("NOT_STARTED", before.path("state").asString());
        assertEquals(DIGEST, before.path("commandDigest").asString());
        String body = idleBody(operation);
        assertEquals("PREPARED", state(call("POST", resource, body, signed("POST", resource, body))));
        assertEquals("PREPARED", state(call("GET", status, "", signed("GET", status, ""))));
        String confirmation = resource + "/" + operation + "/confirmation";
        String receipt = closedReceipt();
        assertEquals("COMPLETED", state(call("POST", confirmation, receipt, signed("POST", confirmation, receipt))));
        assertEquals("COMPLETED", state(call("GET", status, "", signed("GET", status, ""))));
    }

    @Test
    void abortBeforePrepareIsFinalForThatOperationAndDoesNotFenceTheSession() throws Exception {
        String resource = preparations(UUID.randomUUID());
        UUID operation = UUID.randomUUID();
        String confirmation = resource + "/" + operation + "/confirmation";
        String status = resource + "/" + operation + "?restaurantId=" + RESTAURANT + "&commandDigest=" + DIGEST;
        String wrongDigest = "e".repeat(64);

        var first = sendSigned(port, "POST", confirmation, notClosed(DIGEST));
        assertEquals("NOT_STARTED", state(first));
        assertEquals(DIGEST, json.readTree(first.body()).path("commandDigest").asString());
        assertEquals("NOT_STARTED", state(sendSigned(port, "POST", confirmation, notClosed(DIGEST))));
        assertEquals("NOT_STARTED", state(sendSigned(port, "GET", status, "")));
        check(sendSigned(port, "POST", confirmation, notClosed(wrongDigest)), 409, "IDEMPOTENCY_CONFLICT");
        check(sendSigned(port, "GET", status.replace(DIGEST, wrongDigest), ""), 409, "IDEMPOTENCY_CONFLICT");
        check(sendSigned(port, "POST", resource, idleBody(operation).replace(DIGEST, wrongDigest)),
                409, "IDEMPOTENCY_CONFLICT");
        check(sendSigned(port, "POST", preparations(UUID.randomUUID()), idleBody(operation)), 409, "IDEMPOTENCY_CONFLICT");
        check(sendSigned(port, "POST", resource, idleBody(operation)), 409, "INVALID_STATUS_TRANSITION");
        assertEquals("PREPARED", state(sendSigned(port, "POST", resource, idleBody(UUID.randomUUID()))));
    }

    @Test
    void anotherOrderInstanceCanRecoverLostResponsesAndFinishAfterThePreparingProcessStops(@TempDir Path directory)
            throws Exception {
        String jdbcUrl;
        try (var connection = jdbc.getDataSource().getConnection()) {
            jdbcUrl = connection.getMetaData().getURL();
        }
        String resource = preparations(UUID.randomUUID());
        UUID operation = UUID.randomUUID();
        String status = resource + "/" + operation + "?restaurantId=" + RESTAURANT + "&commandDigest=" + DIGEST;
        String confirmation = resource + "/" + operation + "/confirmation";
        String prepareBody = idleBody(operation);
        String competingBody = idleBody(UUID.randomUUID());
        String abortResource = preparations(UUID.randomUUID());
        UUID aborted = UUID.randomUUID();
        try (var first = startOrder(directory.resolve("first"), jdbcUrl)) {
            sendSigned(first.port(), "POST", resource, prepareBody); // QR has to recover without this response.
            assertEquals("PREPARED", state(sendSigned(port, "GET", status, "")));
            check(sendSigned(port, "POST", resource, competingBody), 409, "CLOSURE_IN_PROGRESS");
            assertEquals("NOT_STARTED", state(sendSigned(first.port(), "POST",
                    abortResource + "/" + aborted + "/confirmation", notClosed(DIGEST))));
        }
        try (var restarted = startOrder(directory.resolve("restarted"), jdbcUrl)) {
            assertEquals("PREPARED", state(sendSigned(restarted.port(), "GET", status, "")));
            check(sendSigned(restarted.port(), "POST", resource, competingBody), 409, "CLOSURE_IN_PROGRESS");
            check(sendSigned(restarted.port(), "POST", abortResource, idleBody(aborted)),
                    409, "INVALID_STATUS_TRANSITION");
            String receipt = closedReceipt();
            sendSigned(restarted.port(), "POST", confirmation, receipt); // Recover a lost CLOSED response as well.
            assertEquals("COMPLETED", state(sendSigned(port, "GET", status, "")));
            assertEquals("COMPLETED", state(sendSigned(port, "POST", confirmation, receipt)));
        }
    }

    private static LifecycleOrderTestProcess startOrder(Path directory, String jdbcUrl) throws Exception {
        return LifecycleOrderTestProcess.start(directory, jdbcUrl, configuredKeys(), RESTAURANT.toString(),
                outboundKey());
    }

    private static String closedReceipt() {
        return """
                {"restaurantId":"%s","outcome":"CLOSED","commandDigest":"%s",
                 "receipt":{"effect":"IDLE_MAIN","sessionVersion":1,"tableVersion":1,"completedAt":"%s","expiredGuests":0}}
                """.formatted(RESTAURANT, DIGEST, now());
    }

    private static String notClosed(String digest) {
        return """
                {"restaurantId":"%s","outcome":"NOT_CLOSED","commandDigest":"%s"}
                """.formatted(RESTAURANT, digest);
    }

    @Test
    void anOrderPreventsRealQrFromUsingLocalPaymentFactsAsClearance() throws Exception {
        UUID session = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO orders (id, restaurant_id, order_no, cart_id, table_id, table_session_id,
                    guest_session_id, fulfillment_status, payment_status, currency, initial_net_minor,
                    initial_vat_minor, initial_gross_minor, current_due_minor, placed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'RECEIVED', 'NOT_REQUESTED', 'TRY', 0, 0, 0, 0, now(), now(), now())
                """, order, RESTAURANT, order.toString(), UUID.randomUUID(), UUID.randomUUID(), session, UUID.randomUUID());
        String path = stateTarget(session, RESTAURANT);
        check(call("GET", path, "", signed("GET", path, "")), 503, "DEPENDENCY_UNAVAILABLE");
        String resource = preparations(session);
        String body = idleBody(UUID.randomUUID());
        check(call("POST", resource, body, signed("POST", resource, body)), 503, "DEPENDENCY_UNAVAILABLE");
    }

    private static String preparations(UUID session) {
        return "/api/v1/internal/table-sessions/" + session + "/closure-preparations";
    }

    private static String stateTarget(UUID session, UUID restaurant) {
        return "/api/v1/internal/table-sessions/" + session + "/order-state?restaurantId=" + restaurant;
    }

    private static String idleBody(UUID operation) {
        return """
                {"restaurantId":"%s","operationId":"%s","purpose":"IDLE","commandDigest":"%s",
                 "requestedAt":"%s","mainIdleExpiresAt":"%s","guests":[]}
                """.formatted(RESTAURANT, operation, DIGEST, now(), now().minusSeconds(60));
    }

    private String state(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body()).path("state").asString();
    }

    private void check(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, json.readTree(response.body()).path("code").asString());
    }

    private HttpResponse<String> call(String method, String path, String body, String credential) throws Exception {
        return call(port, method, path, body, credential);
    }

    private HttpResponse<String> sendSigned(int instancePort, String method, String path, String body) throws Exception {
        return call(instancePort, method, path, body, signed(method, path, body));
    }

    private HttpResponse<String> call(int instancePort, String method, String path, String body, String credential)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + instancePort + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").method(method, body.isEmpty()
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (credential != null) {
            request.header("Authorization", credential);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String signed(String method, String target, String body) {
        return token(method, target, body, RESTAURANT, SUBJECT, AUDIENCE, SCOPE, KEY, now());
    }

    private static String token(String method, String target, String body, UUID tenant, String subject, String audience,
                                String scope, KeyPair key, Instant issued) {
        return SERVICE + Jwts.builder().header().keyId("current").and().issuer("restofy-qr").subject(subject)
                .audience().add(audience).and().id(UUID.randomUUID().toString()).issuedAt(Date.from(issued))
                .notBefore(Date.from(issued)).expiration(Date.from(issued.plusSeconds(30)))
                .claim("token_type", "service").claim("scope", scope).claim("restaurant_id", tenant.toString())
                .claim("method", method).claim("target", target)
                .claim("body_sha256", SignedServiceRequest.digest(body.getBytes(StandardCharsets.UTF_8)))
                .signWith(key.getPrivate(), Jwts.SIG.RS256).compact();
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }
}

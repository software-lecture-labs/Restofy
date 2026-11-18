package com.restofy.qr.infrastructure.client;

import com.restofy.qr.domain.OrderSessionState.Knowledge;
import com.restofy.qr.domain.SessionTransition.*;
import com.restofy.qr.domain.enums.GuestSessionStatus;
import com.restofy.qr.domain.enums.TableOperationalStatus;
import com.restofy.qr.domain.enums.TableSessionStatus;
import com.restofy.qr.domain.exception.SessionClosureUnavailableException;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class OrderLifecycleClientTests {
    private static final KeyPair KEYS = Jwts.SIG.RS256.keyPair().build();
    private static final Instant NOW = Instant.parse("2026-11-18T18:00:00Z");
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID TABLE = UUID.randomUUID();
    private static final String BASE = "/api/v1/internal/table-sessions/" + SESSION;

    private final JsonMapper json = JsonMapper.builder().build();
    private final LinkedBlockingQueue<Request> requests = new LinkedBlockingQueue<>();
    private volatile String payload;
    private volatile int status = 200;
    private volatile boolean loseResponse;
    private volatile CountDownLatch holdResponse;
    private HttpServer server;
    private OrderLifecycleClient client;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                        exchange.getRequestHeaders().getFirst("Authorization"), exchange.getRequestBody().readAllBytes()));
                if (loseResponse) { return; }
                var hold = holdResponse;
                if (hold != null) {
                    try { hold.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException _) { Thread.currentThread().interrupt(); }
                    return;
                }
                byte[] reply = payload.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, reply.length);
                exchange.getResponseBody().write(reply);
            }
        });
        server.start();
        client = client(new MockEnvironment().withProperty("spring.profiles.active", "test"));
    }

    @AfterEach
    void stopServer() { server.stop(0); }

    @Test
    void verifiesSignedReadAndDoesNotTurnPaymentFailureOrWrongTenantIntoEmptySession() throws Exception {
        payload = """
                {"restaurantId":"%s","tableSessionId":"%s","knowledge":"NO_ORDERS","revision":1,
                 "verifiedAt":"%s","validUntil":"%s","protectedGuests":[],"cancellationReleases":{},"extra":true}
                """.formatted(TENANT, SESSION, NOW, NOW.plusSeconds(60));
        assertEquals(Knowledge.NO_ORDERS, client.inspect(TENANT, SESSION).knowledge());
        signedRequest("GET", BASE + "/order-state?restaurantId=" + TENANT);

        status = 503;
        assertEquals(Knowledge.UNKNOWN, client.inspect(TENANT, SESSION).knowledge());
        signedRequest("GET", BASE + "/order-state?restaurantId=" + TENANT);
        assertEquals(Knowledge.UNKNOWN, client.inspect(UUID.randomUUID(), SESSION).knowledge());
        assertTrue(requests.isEmpty());
        var production = new MockEnvironment();
        assertThrows(IllegalStateException.class, () -> client(production));
    }

    @Test
    void capsIdleCandidatesAndUsesTheSameDurableCommandWithAFreshSignatureAfterFailure() throws Exception {
        Map<UUID, GuestTarget> guests = new HashMap<>();
        for (int index = 1; index <= 501; index++) {
            guests.put(new UUID(0, index), new GuestTarget(0, GuestSessionStatus.ACTIVE, NOW.minusSeconds(900), NOW));
        }
        guests.put(new UUID(0, 0), new GuestTarget(0, GuestSessionStatus.ACTIVE, NOW.minusSeconds(900), null));
        var command = command(guests);
        String resource = BASE + "/closure-preparations";
        payload = "{}";
        status = 503;
        assertThrows(SessionClosureUnavailableException.class, () -> client.prepare(command));
        var failed = signedRequest("POST", resource);
        var body = json.readTree(failed.body());
        assertEquals(command.digest(), body.get("commandDigest").asString());
        assertEquals(command.operationId().toString(), body.get("operationId").asString());
        assertEquals(500, body.get("guests").size());
        assertEquals(new UUID(0, 1).toString(), body.get("guests").get(0).get("guestSessionId").asString());
        assertEquals(new UUID(0, 500).toString(), body.get("guests").get(499).get("guestSessionId").asString());

        status = 200;
        payload = response(command, "NOT_STARTED", "NOT_STARTED", "NONE", "null", false);
        assertEquals(SourceStatus.NOT_STARTED, client.status(command).status());
        signedRequest("GET", resource + "/" + command.operationId() + "?restaurantId=" + TENANT + "&commandDigest=" + command.digest());
        payload = response(command, "PREPARED", "AUTHORISED", "IDLE_MAIN", "\"IDLE\"", false);
        assertTrue(client.prepare(command).permits(command, NOW));
        var retried = signedRequest("POST", resource);
        assertArrayEquals(failed.body(), retried.body());
        assertNotEquals(claims(failed).getId(), claims(retried).getId());
    }

    @Test
    void acceptsBoundAbortAndK13AcknowledgementAndRejectsMismatchedDigest() throws Exception {
        var command = command(Map.of());
        String confirmation = BASE + "/closure-preparations/" + command.operationId() + "/confirmation";
        payload = response(command, "NOT_STARTED", "NOT_STARTED", "NONE", "null", false);
        assertTrue(client.abort(command).confirmsAbort(command, NOW));
        assertEquals("NOT_CLOSED", json.readTree(signedRequest("POST", confirmation).body()).get("outcome").asString());

        var result = new LocalResult(command.operationId(), TENANT, SESSION, TABLE, Effect.IDLE_MAIN,
                TableSessionStatus.EXPIRED, 2, TableOperationalStatus.AVAILABLE, 3, NOW, 0);
        payload = response(command, "COMPLETED", "AUTHORISED", "IDLE_MAIN", "\"IDLE\"", true);
        var confirmed = client.acknowledge(command, result);
        assertTrue(confirmed.confirms(command, result, NOW));
        assertEquals(List.of("RECONCILIATION_REQUIRED"), confirmed.driftBlockers());
        JsonNode sent = json.readTree(signedRequest("POST", confirmation).body());
        assertEquals("CLOSED", sent.get("outcome").asString());
        assertEquals(3, sent.get("receipt").get("tableVersion").asInt());

        payload = payload.replace(command.digest(), "0".repeat(64));
        assertThrows(SessionClosureUnavailableException.class, () -> client.acknowledge(command, result));
    }

    @Test
    void conflictAndLostResponseNeverAuthorizeAnAbortOrTriggerABlindPostRetry() throws Exception {
        var command = command(Map.of());
        String confirmation = BASE + "/closure-preparations/" + command.operationId() + "/confirmation";
        payload = "{}";
        status = 409;
        assertThrows(SessionClosureUnavailableException.class, () -> client.abort(command));
        signedRequest("POST", confirmation);
        loseResponse = true;
        assertThrows(SessionClosureUnavailableException.class, () -> client.abort(command));
        signedRequest("POST", confirmation);
        assertTrue(requests.isEmpty());
    }

    @Test
    void readTimeoutKeepsTheSameOperationAvailableForStatusLookup() throws Exception {
        var command = command(Map.of());
        var profile = new MockEnvironment().withProperty("spring.profiles.active", "test");
        var shortTimeout = client(profile, 300);
        var release = new CountDownLatch(1);
        holdResponse = release;
        try {
            assertThrows(SessionClosureUnavailableException.class, () -> shortTimeout.prepare(command));
            signedRequest("POST", BASE + "/closure-preparations");
            assertTrue(requests.isEmpty());
        } finally {
            holdResponse = null;
            release.countDown();
        }
        payload = response(command, "NOT_STARTED", "NOT_STARTED", "NONE", "null", false);
        assertEquals(SourceStatus.NOT_STARTED, client.status(command).status());
        signedRequest("GET", BASE + "/closure-preparations/" + command.operationId()
                + "?restaurantId=" + TENANT + "&commandDigest=" + command.digest());
    }

    private OrderLifecycleClient client(MockEnvironment environment) {
        return client(environment, 2000);
    }

    private OrderLifecycleClient client(MockEnvironment environment, long readTimeout) {
        return new OrderLifecycleClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), TENANT,
                "test-key", Base64.getEncoder().encodeToString(KEYS.getPrivate().getEncoded()), 1000, readTimeout,
                environment, Clock.fixed(NOW, ZoneOffset.UTC), json);
    }

    private static Command command(Map<UUID, GuestTarget> guests) {
        return new Command(UUID.randomUUID(), Purpose.IDLE,
                new Snapshot(TENANT, SESSION, TABLE, 0, 0, NOW.minusSeconds(1000), NOW.minusSeconds(900), NOW, guests), NOW);
    }

    private static String response(Command command, String state, String status, String effect, String purpose, boolean drift) {
        return """
                {"operationId":"%s","restaurantId":"%s","tableSessionId":"%s","purpose":%s,
                 "commandDigest":"%s","state":"%s","sourceStatus":"%s","effect":"%s","sourceRevision":1,
                 "verifiedAt":"%s","validUntil":"%s","complete":true,"checkoutFenced":true,"idleGuestIds":[],
                 "scopeDrift":%s,"driftBlockers":%s,"extra":"ignored"}
                """.formatted(command.operationId(), TENANT, SESSION, purpose, command.digest(), state, status,
                effect, NOW, NOW.plusSeconds(60), drift, drift ? "[\"RECONCILIATION_REQUIRED\"]" : "[]");
    }

    private Request signedRequest(String method, String target) throws Exception {
        var request = requests.remove();
        assertEquals(method, request.method());
        assertEquals(target, request.target());
        var claims = claims(request);
        assertEquals(method, claims.get("method"));
        assertEquals(target, claims.get("target"));
        assertEquals(TENANT.toString(), claims.get("restaurant_id"));
        assertEquals("table-session:lifecycle", claims.get("scope"));
        assertEquals(claims.getIssuedAt(), claims.getNotBefore());
        assertEquals(Date.from(NOW.plusSeconds(30)), claims.getExpiration());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(request.body())),
                claims.get("body_sha256"));
        return request;
    }

    private static Claims claims(Request request) {
        assertTrue(request.authorization().startsWith("Service "));
        var token = Jwts.parser().verifyWith((RSAPublicKey) KEYS.getPublic()).clock(() -> Date.from(NOW))
                .requireIssuer("restofy-qr").requireSubject("qr-service").requireAudience("restofy-order").build()
                .parseSignedClaims(request.authorization().substring("Service ".length()));
        assertEquals("test-key", token.getHeader().getKeyId());
        assertEquals("RS256", token.getHeader().getAlgorithm());
        return token.getPayload();
    }

    private record Request(String method, String target, String authorization, byte[] body) { }
}

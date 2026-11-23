package com.restofy.qr.infrastructure;

import com.restofy.qr.api.dto.request.OrderCartActivity;
import com.restofy.qr.application.port.OrderSessionAccess;
import com.restofy.qr.domain.OrderSessionState;
import com.restofy.qr.infrastructure.security.GuestTokenCodec;
import com.restofy.qr.infrastructure.security.JwtDates;
import com.restofy.qr.support.Q5TestSecuritySupport;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * The Waiter service has its own credential filter and chain; on the guest routes it shares with Order, the unverified
 * key ID only chooses the chain. These tests pin that Waiter works and that Order's identity is untouched.
 */
@ActiveProfiles({"test", "qr-order-inbound"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "qr.payload.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class WaiterGuestSessionHttpTests extends Q5TestSecuritySupport {
    private static final KeyPair ORDER = Jwts.SIG.RS256.keyPair().build();
    private static final KeyPair WAITER = Jwts.SIG.RS256.keyPair().build();
    private static final String ORDER_IDENTITY = "restofy-order";
    private static final String WAITER_IDENTITY = "restofy-waiter";
    private static final String ORDER_SUBJECT = "order-service";
    private static final String WAITER_SUBJECT = "waiter-service";
    private static final String ORDER_SCOPE = "qr:guest-session";
    private static final String WAITER_SCOPE = "qr:guest-validate";
    private static final String ORDER_KEY_ID = "order-test";
    private static final String WAITER_KEY_ID = "waiter-test";
    private static final UUID TENANT = UUID.randomUUID();
    private static final String BASE = "/api/v1/internal/qr";
    private static final String VALIDATE = BASE + "/guest-sessions/validate";
    private static final String ACTIVITY = BASE + "/events/cart-activity";
    private static final String TABLES = "/api/v1/internal/tables";
    private static final String AUTHORIZATION = "Authorization";
    private static final String POST = "POST";
    private static final String GET = "GET";
    private static final String EMPTY = "";
    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired GuestTokenCodec tokens;
    @MockitoBean OrderSessionAccess orders;

    @DynamicPropertySource
    static void trust(DynamicPropertyRegistry registry) {
        registry.add("AUTH_RESTAURANT_ID", TENANT::toString);
        registry.add("QR_ORDER_CALLER_PUBLIC_KEYS", () -> "{\"order-test\":\"" + encoded(ORDER.getPublic().getEncoded()) + "\"}");
        registry.add("QR_WAITER_CALLER_PUBLIC_KEYS", () -> "{\"waiter-test\":\"" + encoded(WAITER.getPublic().getEncoded()) + "\"}");
    }

    @Test
    void waiterValidatesAndRechecksStateWithItsOwnIdentityWithoutRefreshingTheSession() throws Exception {
        availableOrderScope();
        var guest = fixture(TENANT, 120);
        var verified = callAsWaiter(POST, VALIDATE, tokenBody(guest.token()));
        assertThat(verified.statusCode()).isEqualTo(200);
        assertThat(json.readTree(verified.body()).get("guestSessionId").asString()).isEqualTo(guest.guestId().toString());
        var state = callAsWaiter(GET, statePath(guest.sessionId(), guest.guestId()), EMPTY);
        assertThat(state.statusCode()).isEqualTo(200);
        assertThat(json.readTree(state.body()).get("state").asString()).isEqualTo("ACTIVE");
        assertThat(lastGuestActivity(guest.guestId())).isEqualTo(guest.openedAt());
        assertThat(lastMainActivity(guest.sessionId())).isEqualTo(guest.openedAt());
        assertThat(callAsWaiter(POST, VALIDATE, tokenBody("a".repeat(43))).statusCode()).isEqualTo(404);
    }

    @Test
    void waiterCannotUseTheOrderOnlyCartActivityEventOrAnyOtherRoute() throws Exception {
        availableOrderScope();
        var guest = fixture(TENANT, 120);
        var event = activity(guest, guest.openedAt().plusSeconds(30));
        assertThat(callAsWaiter(POST, ACTIVITY, json.writeValueAsString(event)).statusCode()).isEqualTo(403);
        assertThat(lastGuestActivity(guest.guestId())).isEqualTo(guest.openedAt());
        assertThat(receipts(event.eventId())).isZero();
        assertThat(callAsWaiter(GET, BASE + "/other", EMPTY).statusCode()).isEqualTo(403);
    }

    @Test
    void callerIdentitiesAreBoundToTheirOwnKeysAndScopes() throws Exception {
        var guest = fixture(TENANT, 120);
        String body = tokenBody(guest.token());
        // A caller's key cannot present another caller's identity.
        assertThat(sendSigned(body, ORDER_KEY_ID, WAITER_IDENTITY, WAITER_SUBJECT, WAITER_SCOPE, ORDER).statusCode()).isEqualTo(401);
        assertThat(sendSigned(body, WAITER_KEY_ID, ORDER_IDENTITY, ORDER_SUBJECT, ORDER_SCOPE, WAITER).statusCode()).isEqualTo(401);
        assertThat(sendSigned(body, WAITER_KEY_ID, WAITER_IDENTITY, ORDER_SUBJECT, WAITER_SCOPE, WAITER).statusCode()).isEqualTo(401);
        // A caller may only present its own scope.
        assertThat(sendSigned(body, WAITER_KEY_ID, WAITER_IDENTITY, WAITER_SUBJECT, ORDER_SCOPE, WAITER).statusCode()).isEqualTo(403);
        assertThat(sendSigned(body, ORDER_KEY_ID, ORDER_IDENTITY, ORDER_SUBJECT, WAITER_SCOPE, ORDER).statusCode()).isEqualTo(403);
        // Unknown key ID, or a Waiter token signed with a foreign key.
        assertThat(sendSigned(body, "unknown-key", WAITER_IDENTITY, WAITER_SUBJECT, WAITER_SCOPE, WAITER).statusCode()).isEqualTo(401);
        assertThat(sendSigned(body, WAITER_KEY_ID, WAITER_IDENTITY, WAITER_SUBJECT, WAITER_SCOPE, STAFF).statusCode()).isEqualTo(401);
        // Both genuine identities still work.
        assertThat(sendSigned(body, WAITER_KEY_ID, WAITER_IDENTITY, WAITER_SUBJECT, WAITER_SCOPE, WAITER).statusCode()).isEqualTo(200);
        assertThat(sendSigned(body, ORDER_KEY_ID, ORDER_IDENTITY, ORDER_SUBJECT, ORDER_SCOPE, ORDER).statusCode()).isEqualTo(200);
    }

    @Test
    void waiterTokenFromAnotherTenantOrWithAChangedBodyIsRejected() throws Exception {
        var guest = fixture(TENANT, 120);
        String body = tokenBody(guest.token());
        String other = signAs(POST, VALIDATE, body, UUID.randomUUID(), WAITER, Instant.now(), WAITER_KEY_ID, WAITER_IDENTITY,
                WAITER_SUBJECT, WAITER_SCOPE);
        assertThat(send(POST, VALIDATE, body, other, false).statusCode()).isEqualTo(403);
        String valid = signAs(POST, VALIDATE, body, TENANT, WAITER, Instant.now(), WAITER_KEY_ID, WAITER_IDENTITY,
                WAITER_SUBJECT, WAITER_SCOPE);
        assertThat(send(POST, VALIDATE, body + " ", valid, false).statusCode()).isEqualTo(403);
        assertThat(send(POST, VALIDATE, body, valid, true).statusCode()).isEqualTo(401);
    }

    @Test
    void orderCredentialsNeverReachWaiterOnlyRoutesAndWaiterOnlyRoutesRejectEverythingElse() throws Exception {
        // An Order key cannot read tables (Waiter-only route): the Waiter chain owns the route and does not know that key.
        String orderToken = signAs(GET, TABLES, EMPTY, TENANT, ORDER, Instant.now(), ORDER_KEY_ID, ORDER_IDENTITY, ORDER_SUBJECT,
                ORDER_SCOPE);
        assertThat(send(GET, TABLES, EMPTY, orderToken, false).statusCode()).isEqualTo(401);
        assertThat(send(GET, TABLES, EMPTY, null, false).statusCode()).isEqualTo(401);
        // A Waiter token without the table-read scope is a scope violation, not an authentication failure.
        String wrongScope = signAs(GET, TABLES, EMPTY, TENANT, WAITER, Instant.now(), WAITER_KEY_ID, WAITER_IDENTITY, WAITER_SUBJECT,
                WAITER_SCOPE);
        assertThat(send(GET, TABLES, EMPTY, wrongScope, false).statusCode()).isEqualTo(403);
        String tableRead = signAs(GET, TABLES, EMPTY, TENANT, WAITER, Instant.now(), WAITER_KEY_ID, WAITER_IDENTITY, WAITER_SUBJECT,
                "qr:table-read");
        assertThat(send(GET, TABLES, EMPTY, tableRead, false).statusCode()).isEqualTo(200);
    }

    private void availableOrderScope() {
        doAnswer(call -> evidence(call.getArgument(1), Set.of())).when(orders).inspect(any(), any());
    }

    private static OrderSessionState evidence(UUID session, Set<UUID> protectedGuests) {
        Instant now = Instant.now();
        var knowledge = protectedGuests.isEmpty() ? OrderSessionState.Knowledge.NO_ORDERS : OrderSessionState.Knowledge.OPEN_WORK;
        return new OrderSessionState(TENANT, session, knowledge, 1, now, now.plusSeconds(60), protectedGuests, Map.of(), null);
    }

    private Guest fixture(UUID tenant, long secondsAgo) {
        UUID table = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        var opened = Instant.now().minusSeconds(secondsAgo).truncatedTo(ChronoUnit.MICROS);
        jdbc.update("""
                INSERT INTO dining_tables(id, restaurant_id, table_number, operational_status, is_active, created_at, updated_at)
                VALUES (?, ?, ?, 'OCCUPIED', true, ?, ?)
                """, table, tenant, table.toString(), Timestamp.from(opened), Timestamp.from(opened));
        jdbc.update("""
                INSERT INTO table_sessions(id, restaurant_id, table_id, status, opened_at, last_activity_at, idle_expires_at)
                VALUES (?, ?, ?, 'OPEN', ?, ?, ?)
                """, session, tenant, table, Timestamp.from(opened), Timestamp.from(opened), Timestamp.from(opened.plusSeconds(900)));
        String token = tokens.generateToken();
        UUID guest = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO guest_sessions(id, restaurant_id, table_session_id, token_digest, status, joined_at, last_activity_at, expires_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
                """, guest, tenant, session, tokens.digest(token), Timestamp.from(opened), Timestamp.from(opened),
                Timestamp.from(opened.plusSeconds(900)));
        return new Guest(table, session, guest, token, opened);
    }

    private static OrderCartActivity activity(Guest guest, Instant time) {
        return new OrderCartActivity(UUID.randomUUID(), "CartActivityRecorded", 1, time, "qr-test", TENANT,
                guest.sessionId(), guest.guestId(), UUID.randomUUID(), OrderCartActivity.Activity.ITEM_ADDED);
    }

    private String tokenBody(String token) { return json.writeValueAsString(Map.of("guestToken", token)); }

    private Instant lastGuestActivity(UUID id) {
        return jdbc.queryForObject("SELECT last_activity_at FROM guest_sessions WHERE id=?", Timestamp.class, id).toInstant();
    }

    private Instant lastMainActivity(UUID id) {
        return jdbc.queryForObject("SELECT last_activity_at FROM table_sessions WHERE id=?", Timestamp.class, id).toInstant();
    }

    private int receipts(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM order_cart_activity_receipts WHERE event_id=?", Integer.class, id);
    }

    private static String statePath(UUID session, UUID guest) { return BASE + "/table-sessions/" + session + "/guests/" + guest + "/state"; }

    private HttpResponse<String> callAsWaiter(String method, String target, String body) throws Exception {
        return send(method, target, body, signAs(method, target, body, TENANT, WAITER, Instant.now(), WAITER_KEY_ID,
                WAITER_IDENTITY, WAITER_SUBJECT, WAITER_SCOPE), false);
    }

    private HttpResponse<String> sendSigned(String body, String keyId, String issuer, String subject, String scope, KeyPair key)
            throws Exception {
        return send(POST, VALIDATE, body, signAs(POST, VALIDATE, body, TENANT, key, Instant.now(), keyId, issuer, subject, scope), false);
    }

    private HttpResponse<String> send(String method, String target, String body, String credential, boolean browser) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + target))
                .method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        if (credential != null) { request.header(AUTHORIZATION, credential); }
        if (browser) { request.header("Cookie", "restofy_access=fixture"); }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String signAs(String method, String target, String body, UUID tenant, KeyPair key, Instant issuedAt,
                                 String keyId, String issuer, String subject, String scope) throws Exception {
        var now = issuedAt.truncatedTo(ChronoUnit.SECONDS);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        return "Service " + Jwts.builder().header().keyId(keyId).and().issuer(issuer).subject(subject)
                .audience().add("restofy-qr").and().id(UUID.randomUUID().toString())
                .issuedAt(JwtDates.from(now)).notBefore(JwtDates.from(now)).expiration(JwtDates.from(now.plusSeconds(30)))
                .claim("token_type", "service").claim("scope", scope).claim("restaurant_id", tenant.toString())
                .claim("method", method).claim("target", target).claim("body_sha256", digest).signWith(key.getPrivate(), Jwts.SIG.RS256).compact();
    }

    private record Guest(UUID tableId, UUID sessionId, UUID guestId, String token, Instant openedAt) { }
}

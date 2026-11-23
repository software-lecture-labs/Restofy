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
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ActiveProfiles({"test", "qr-order-inbound"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "qr.payload.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class OrderGuestSessionHttpTests extends Q5TestSecuritySupport {
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
    static void orderTrust(DynamicPropertyRegistry registry) {
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
    void validatesTokenFromQrDataAndRechecksCheckoutWithoutRefreshingTheSession() throws Exception {
        availableOrderScope();
        var guest = fixture(TENANT, 120);
        var body = tokenBody(guest.token());
        var verified = call(POST, VALIDATE, body);
        assertThat(verified.statusCode()).isEqualTo(200);
        var context = json.readTree(verified.body());
        assertThat(context.get("restaurantId").asString()).isEqualTo(TENANT.toString());
        assertThat(context.get("tableId").asString()).isEqualTo(guest.tableId().toString());
        assertThat(context.get("tableSessionId").asString()).isEqualTo(guest.sessionId().toString());
        assertThat(context.get("guestSessionId").asString()).isEqualTo(guest.guestId().toString());
        assertThat(context.get("state").asString()).isEqualTo("ACTIVE");
        assertState(guest, "ACTIVE");
        assertThat(lastGuestActivity(guest.guestId())).isEqualTo(guest.openedAt());
        assertThat(lastMainActivity(guest.sessionId())).isEqualTo(guest.openedAt());
        assertThat(call(POST, VALIDATE, tokenBody(fixture(UUID.randomUUID(), 120).token())).statusCode()).isEqualTo(404);
        assertThat(call(POST, VALIDATE, tokenBody("a".repeat(43))).statusCode()).isEqualTo(404);
        assertThat(call(GET, statePath(guest.sessionId(), UUID.randomUUID()), EMPTY).statusCode()).isEqualTo(404);
        jdbc.update("UPDATE table_sessions SET status='CLOSING', closing_at=now() WHERE id=?", guest.sessionId());
        assertState(guest, "CLOSED");
        assertThat(call(POST, VALIDATE, body).statusCode()).isEqualTo(404);
    }

    @Test
    void distinguishesExpiredStateFromUnavailableOrderEvidenceAndHonorsOrderProtection() throws Exception {
        var guest = fixture(TENANT, 1200);
        when(orders.inspect(TENANT, guest.sessionId())).thenReturn(OrderSessionState.unknown(TENANT, guest.sessionId()));
        assertThat(call(GET, statePath(guest.sessionId(), guest.guestId()), EMPTY).statusCode()).isEqualTo(503);
        assertThat(call(POST, VALIDATE, tokenBody(guest.token())).statusCode()).isEqualTo(503);
        availableOrderScope();
        assertState(guest, "EXPIRED");
        assertThat(call(POST, VALIDATE, tokenBody(guest.token())).statusCode()).isEqualTo(404);
        when(orders.inspect(TENANT, guest.sessionId())).thenReturn(evidence(guest.sessionId(), Set.of(guest.guestId())));
        assertState(guest, "ACTIVE");
    }

    @Test
    void asksOrderOnlyWhenLocalTimersCannotProveActive() throws Exception {
        when(orders.inspect(any(), any())).thenThrow(new IllegalStateException("Order must not be asked"));
        var active = fixture(TENANT, 120);
        assertThat(call(POST, VALIDATE, tokenBody(active.token())).statusCode()).isEqualTo(200);
        assertState(active, "ACTIVE");
        Instant occurredAt = active.openedAt().plusSeconds(30);
        assertThat(call(POST, ACTIVITY, json.writeValueAsString(activity(active, occurredAt))).statusCode()).isEqualTo(204);
        assertThat(lastGuestActivity(active.guestId())).isEqualTo(occurredAt);
        var closed = fixture(TENANT, 120);
        jdbc.update("UPDATE table_sessions SET status='CLOSED', closed_at=now() WHERE id=?", closed.sessionId());
        assertState(closed, "CLOSED");
        // Checked per session: the idle sweep job may ask Order about other, really idle sessions meanwhile.
        verify(orders, never()).inspect(TENANT, active.sessionId());
        verify(orders, never()).inspect(TENANT, closed.sessionId());

        availableOrderScope();
        var idle = fixture(TENANT, 1200);
        assertState(idle, "EXPIRED");
        verify(orders, atLeastOnce()).inspect(TENANT, idle.sessionId());
    }

    @Test
    void rejectsMissingWrongTenantWrongKeyAndTamperedCredentialsBeforeParsingOrWriting() throws Exception {
        var guest = fixture(TENANT, 120);
        String body = tokenBody(guest.token());
        String authorization = sign(POST, VALIDATE, body, TENANT, ORDER);
        assertThat(send(POST, VALIDATE, body, null, false).statusCode()).isEqualTo(401);
        assertThat(send(POST, VALIDATE, body, sign(POST, VALIDATE, body, UUID.randomUUID(), ORDER), false).statusCode()).isEqualTo(403);
        assertThat(send(POST, VALIDATE, body, sign(POST, VALIDATE, body, TENANT, STAFF), false).statusCode()).isEqualTo(401);
        assertThat(send(POST, VALIDATE, body, sign(POST, VALIDATE, body, TENANT, ORDER, Instant.now().minusSeconds(31)), false)
                .statusCode()).isEqualTo(401);
        assertThat(send(POST, VALIDATE, body + " ", authorization, false).statusCode()).isEqualTo(403);
        assertThat(send(POST, ACTIVITY, body, authorization, false).statusCode()).isEqualTo(403);
        assertThat(send(POST, VALIDATE, body, authorization, true).statusCode()).isEqualTo(401);
        assertThat(call(POST, VALIDATE, "{\"guestToken\":\"bad\"}").statusCode()).isEqualTo(400);
        assertThat(call(POST, VALIDATE, " ".repeat(16 * 1024 + 1)).statusCode()).isEqualTo(413);
    }

    @Test
    void refreshesOnlySourceGuestAndMainOnceAtEventTimeAndRejectsAChangedReplay() throws Exception {
        availableOrderScope();
        var guest = fixture(TENANT, 120);
        UUID other = insertGuest(TENANT, guest.sessionId(), tokens.generateToken(), guest.openedAt());
        Instant occurredAt = guest.openedAt().plusSeconds(30);
        var event = activity(guest, occurredAt);
        String body = json.writeValueAsString(event);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> call(POST, ACTIVITY, body));
            var second = executor.submit(() -> call(POST, ACTIVITY, body));
            assertThat(first.get().statusCode()).isEqualTo(204);
            assertThat(second.get().statusCode()).isEqualTo(204);
        }
        assertThat(lastGuestActivity(guest.guestId())).isEqualTo(occurredAt);
        assertThat(lastMainActivity(guest.sessionId())).isEqualTo(occurredAt);
        assertThat(lastGuestActivity(other)).isEqualTo(guest.openedAt());
        long revision = guestVersion(guest.guestId());
        assertThat(call(POST, ACTIVITY, body).statusCode()).isEqualTo(204);
        assertThat(guestVersion(guest.guestId())).isEqualTo(revision);
        assertThat(receipts(event.eventId())).isEqualTo(1);
        var changed = body.replace("ITEM_ADDED", "ITEM_REMOVED");
        assertThat(call(POST, ACTIVITY, changed).statusCode()).isEqualTo(409);
        assertThat(call(POST, ACTIVITY, json.writeValueAsString(activity(guest, occurredAt.minusSeconds(1)))).statusCode())
                .isEqualTo(204);
        assertThat(lastGuestActivity(guest.guestId())).isEqualTo(occurredAt);
    }

    @Test
    void protectsClosedOrExpiredSessionsAndRollsBackTheReceiptWhileClosing() throws Exception {
        availableOrderScope();
        var expired = fixture(TENANT, 1200);
        var expiredEvent = activity(expired, expired.openedAt().plusSeconds(30));
        assertThat(call(POST, ACTIVITY, json.writeValueAsString(expiredEvent)).statusCode()).isEqualTo(204);
        assertThat(lastGuestActivity(expired.guestId())).isEqualTo(expired.openedAt());
        assertState(expired, "EXPIRED");

        var closing = fixture(TENANT, 120);
        var delayed = activity(closing, closing.openedAt().plusSeconds(30));
        jdbc.update("UPDATE table_sessions SET status='CLOSING', closing_at=now() WHERE id=?", closing.sessionId());
        var body = json.writeValueAsString(delayed);
        assertThat(call(POST, ACTIVITY, body).statusCode()).isEqualTo(503);
        assertThat(receipts(delayed.eventId())).isZero();
        jdbc.update("UPDATE table_sessions SET status='CLOSED', closed_at=now() WHERE id=?", closing.sessionId());
        assertThat(call(POST, ACTIVITY, body).statusCode()).isEqualTo(204);
        assertThat(lastGuestActivity(closing.guestId())).isEqualTo(closing.openedAt());
        assertState(closing, "CLOSED");
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
        return new Guest(table, session, insertGuest(tenant, session, token, opened), token, opened);
    }

    private UUID insertGuest(UUID tenant, UUID session, String token, Instant opened) {
        UUID guest = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO guest_sessions(id, restaurant_id, table_session_id, token_digest, status, joined_at, last_activity_at, expires_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
                """, guest, tenant, session, tokens.digest(token), Timestamp.from(opened), Timestamp.from(opened),
                Timestamp.from(opened.plusSeconds(900)));
        return guest;
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

    private long guestVersion(UUID id) { return jdbc.queryForObject("SELECT version FROM guest_sessions WHERE id=?", Long.class, id); }

    private int receipts(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM order_cart_activity_receipts WHERE event_id=?", Integer.class, id);
    }

    private void assertState(Guest guest, String expected) throws Exception {
        var response = call(GET, statePath(guest.sessionId(), guest.guestId()), EMPTY);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).get("state").asString()).isEqualTo(expected);
    }

    private static String statePath(UUID session, UUID guest) { return BASE + "/table-sessions/" + session + "/guests/" + guest + "/state"; }

    private HttpResponse<String> call(String method, String target, String body) throws Exception {
        return send(method, target, body, sign(method, target, body, TENANT, ORDER), false);
    }

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

    private static String sign(String method, String target, String body, UUID tenant, KeyPair key) throws Exception {
        return sign(method, target, body, tenant, key, Instant.now());
    }

    private static String sign(String method, String target, String body, UUID tenant, KeyPair key, Instant issuedAt) throws Exception {
        return signAs(method, target, body, tenant, key, issuedAt, ORDER_KEY_ID, ORDER_IDENTITY, ORDER_SUBJECT, ORDER_SCOPE);
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

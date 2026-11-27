package com.restofy.qr.infrastructure;

import com.restofy.qr.application.port.OrderSessionAccess;
import com.restofy.qr.domain.OrderSessionState;
import com.restofy.qr.infrastructure.security.GuestTokenCodec;
import com.restofy.qr.infrastructure.security.JwtDates;
import com.restofy.qr.support.Q5TestSecuritySupport;
import io.jsonwebtoken.JwtBuilder;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QR-REQUEST-01: Menu's own guest check. Menu has its own key, identity and route; Order and Waiter routes and
 * credentials stay separate in both directions.
 */
@ActiveProfiles({"test", "qr-order-inbound"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "qr.payload.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class MenuGuestSessionHttpTests extends Q5TestSecuritySupport {
    private static final KeyPair MENU = Jwts.SIG.RS256.keyPair().build();
    private static final KeyPair ORDER = Jwts.SIG.RS256.keyPair().build();
    private static final KeyPair WAITER = Jwts.SIG.RS256.keyPair().build();
    private static final UUID TENANT = UUID.randomUUID();
    private static final String MENU_VALIDATE = "/api/v1/internal/qr/menu/guest-sessions/validate";
    private static final String ORDER_VALIDATE = "/api/v1/internal/qr/guest-sessions/validate";
    private static final String POST = "POST";
    private static final String MENU_ISSUER = "restofy-menu";
    private static final String MENU_SUBJECT = "menu-service";
    private static final String MENU_SCOPE = "qr:menu-guest-validate";
    private static final String MENU_KEY_ID = "menu-test";
    private static final String CODE = "code";
    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired GuestTokenCodec tokens;
    @MockitoBean OrderSessionAccess orders;

    @DynamicPropertySource
    static void trust(DynamicPropertyRegistry registry) {
        registry.add("AUTH_RESTAURANT_ID", TENANT::toString);
        registry.add("QR_MENU_CALLER_PUBLIC_KEYS", () -> "{\"menu-test\":\"" + encoded(MENU.getPublic().getEncoded()) + "\"}");
        registry.add("QR_ORDER_CALLER_PUBLIC_KEYS", () -> "{\"order-test\":\"" + encoded(ORDER.getPublic().getEncoded()) + "\"}");
        registry.add("QR_WAITER_CALLER_PUBLIC_KEYS", () -> "{\"waiter-test\":\"" + encoded(WAITER.getPublic().getEncoded()) + "\"}");
    }

    @Test
    void menuLearnsOnlyTheRestaurantAndStateWithoutRefreshingOrAskingOrder() throws Exception {
        when(orders.inspect(any(), any())).thenThrow(new IllegalStateException("Order must not be asked"));
        var guest = fixture(TENANT, 120);
        var response = callAsMenu(tokenBody(guest.token()));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        var body = json.readTree(response.body());
        List<String> fields = new ArrayList<>(body.propertyNames());
        assertThat(fields).containsExactlyInAnyOrder("restaurantId", "state");
        assertThat(body.get("restaurantId").asString()).isEqualTo(TENANT.toString());
        assertThat(body.get("state").asString()).isEqualTo("ACTIVE");
        assertThat(lastGuestActivity(guest.guestId())).isEqualTo(guest.openedAt());
        assertThat(lastMainActivity(guest.sessionId())).isEqualTo(guest.openedAt());
        verify(orders, never()).inspect(TENANT, guest.sessionId());
    }

    @Test
    void unknownClosedOrForeignGuestsGetTheSameStable404() throws Exception {
        var closed = fixture(TENANT, 120);
        jdbc.update("UPDATE table_sessions SET status='CLOSED', closed_at=now() WHERE id=?", closed.sessionId());
        var refusals = List.of(
                callAsMenu(tokenBody("a".repeat(43))),
                callAsMenu(tokenBody(closed.token())),
                callAsMenu(tokenBody(fixture(UUID.randomUUID(), 120).token())));
        for (var refusal : refusals) {
            assertThat(refusal.statusCode()).isEqualTo(404);
            assertThat(json.readTree(refusal.body()).get(CODE).asString()).isEqualTo("GUEST_SESSION_UNAVAILABLE");
        }
        assertThat(callAsMenu("{\"guestToken\":\"bad\"}").statusCode()).isEqualTo(400);
    }

    @Test
    void asksOrderWhenLocalTimersRanOutLikeEveryOtherConsumer() throws Exception {
        var idle = fixture(TENANT, 1200);
        when(orders.inspect(TENANT, idle.sessionId())).thenReturn(OrderSessionState.unknown(TENANT, idle.sessionId()));
        assertThat(callAsMenu(tokenBody(idle.token())).statusCode()).isEqualTo(503);
        doAnswer(call -> evidence(call.getArgument(1), Set.of())).when(orders).inspect(any(), any());
        assertThat(callAsMenu(tokenBody(idle.token())).statusCode()).isEqualTo(404);
        when(orders.inspect(TENANT, idle.sessionId())).thenReturn(evidence(idle.sessionId(), Set.of(idle.guestId())));
        assertThat(callAsMenu(tokenBody(idle.token())).statusCode()).isEqualTo(200);
        assertThat(lastGuestActivity(idle.guestId())).isEqualTo(idle.openedAt());
    }

    @Test
    void rejectsForeignOrMalformedMenuCredentials() throws Exception {
        var guest = fixture(TENANT, 120);
        String body = tokenBody(guest.token());
        assertThat(send(MENU_VALIDATE, body, null, Map.of()).statusCode()).isEqualTo(401);
        // Identity: issuer, subject, key ID and signature must all be Menu's.
        assertThat(sendAsMenu(body, jwt -> jwt.issuer("restofy-order")).statusCode()).isEqualTo(401);
        assertThat(sendAsMenu(body, jwt -> jwt.subject("order-service")).statusCode()).isEqualTo(401);
        assertThat(sendAsMenu(body, jwt -> jwt.header().keyId("unknown-key").and()).statusCode()).isEqualTo(401);
        assertThat(send(MENU_VALIDATE, body, menuToken(POST, MENU_VALIDATE, body, Instant.now(), WAITER, UnaryOperator.identity()),
                Map.of()).statusCode()).isEqualTo(401);
        // Lifetime: expired, or longer than 30 seconds.
        assertThat(send(MENU_VALIDATE, body, menuToken(POST, MENU_VALIDATE, body, Instant.now().minusSeconds(31), MENU,
                UnaryOperator.identity()), Map.of()).statusCode()).isEqualTo(401);
        assertThat(sendAsMenu(body, jwt -> jwt.expiration(JwtDates.from(Instant.now().plusSeconds(120)))).statusCode()).isEqualTo(401);
        // Binding: scope, restaurant claim (token-only), target and body.
        assertThat(sendAsMenu(body, jwt -> jwt.claim("scope", "qr:guest-validate")).statusCode()).isEqualTo(403);
        assertThat(sendAsMenu(body, jwt -> jwt.claim("restaurant_id", TENANT.toString())).statusCode()).isEqualTo(403);
        assertThat(sendAsMenu(body, jwt -> jwt.claim("target", ORDER_VALIDATE)).statusCode()).isEqualTo(403);
        String valid = menuToken(POST, MENU_VALIDATE, body, Instant.now(), MENU, UnaryOperator.identity());
        assertThat(send(MENU_VALIDATE, body + " ", valid, Map.of()).statusCode()).isEqualTo(403);
        // Browser material is never accepted from a service caller.
        assertThat(send(MENU_VALIDATE, body, valid, Map.of("Cookie", "restofy_guest=" + guest.token())).statusCode()).isEqualTo(401);
        assertThat(send(MENU_VALIDATE, body, valid, Map.of("Origin", "http://localhost:3000")).statusCode()).isEqualTo(401);
        assertThat(callAsMenu(" ".repeat(1025)).statusCode()).isEqualTo(413);
        assertThat(lastGuestActivity(guest.guestId())).isEqualTo(guest.openedAt());
    }

    @Test
    void menuOrderAndWaiterCannotUseEachOthersRoutes() throws Exception {
        doAnswer(call -> evidence(call.getArgument(1), Set.of())).when(orders).inspect(any(), any());
        var guest = fixture(TENANT, 120);
        String body = tokenBody(guest.token());
        // Order and Waiter credentials, correctly bound to the Menu route, are not Menu's.
        assertThat(send(MENU_VALIDATE, body, serviceToken(MENU_VALIDATE, body, ORDER, "order-test", "restofy-order", "order-service",
                "qr:guest-session"), Map.of()).statusCode()).isEqualTo(401);
        assertThat(send(MENU_VALIDATE, body, serviceToken(MENU_VALIDATE, body, WAITER, "waiter-test", "restofy-waiter",
                "waiter-service", "qr:guest-validate"), Map.of()).statusCode()).isEqualTo(401);
        // A Menu credential bound to the shared Order/Waiter route is not accepted there.
        assertThat(send(ORDER_VALIDATE, body, menuToken(POST, ORDER_VALIDATE, body, Instant.now(), MENU, UnaryOperator.identity()),
                Map.of()).statusCode()).isEqualTo(401);
        // Any other route under Menu's prefix is closed.
        String other = "/api/v1/internal/qr/menu/other";
        assertThat(send(other, body, menuToken(POST, other, body, Instant.now(), MENU, UnaryOperator.identity()), Map.of())
                .statusCode()).isEqualTo(403);
        // The shared Order route still serves Order.
        assertThat(send(ORDER_VALIDATE, body, serviceToken(ORDER_VALIDATE, body, ORDER, "order-test", "restofy-order",
                "order-service", "qr:guest-session"), Map.of()).statusCode()).isEqualTo(200);
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
        return new Guest(session, guest, token, opened);
    }

    private String tokenBody(String token) { return json.writeValueAsString(Map.of("guestToken", token)); }

    private Instant lastGuestActivity(UUID id) {
        return jdbc.queryForObject("SELECT last_activity_at FROM guest_sessions WHERE id=?", Timestamp.class, id).toInstant();
    }

    private Instant lastMainActivity(UUID id) {
        return jdbc.queryForObject("SELECT last_activity_at FROM table_sessions WHERE id=?", Timestamp.class, id).toInstant();
    }

    private HttpResponse<String> callAsMenu(String body) throws Exception {
        return sendAsMenu(body, UnaryOperator.identity());
    }

    private HttpResponse<String> sendAsMenu(String body, UnaryOperator<JwtBuilder> change) throws Exception {
        return send(MENU_VALIDATE, body, menuToken(POST, MENU_VALIDATE, body, Instant.now(), MENU, change), Map.of());
    }

    private HttpResponse<String> send(String target, String body, String credential, Map<String, String> headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + target))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        if (credential != null) { request.header("Authorization", credential); }
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A Menu credential: no restaurant_id claim, one scope, bound to method, target and body. */
    private static String menuToken(String method, String target, String body, Instant issuedAt, KeyPair key,
                                    UnaryOperator<JwtBuilder> change) throws Exception {
        var now = issuedAt.truncatedTo(ChronoUnit.SECONDS);
        var jwt = Jwts.builder().header().keyId(MENU_KEY_ID).and().issuer(MENU_ISSUER).subject(MENU_SUBJECT)
                .audience().add("restofy-qr").and().id(UUID.randomUUID().toString())
                .issuedAt(JwtDates.from(now)).notBefore(JwtDates.from(now)).expiration(JwtDates.from(now.plusSeconds(30)))
                .claim("token_type", "service").claim("scope", MENU_SCOPE)
                .claim("method", method).claim("target", target).claim("body_sha256", digest(body));
        return "Service " + change.apply(jwt).signWith(key.getPrivate(), Jwts.SIG.RS256).compact();
    }

    /** An Order or Waiter credential, as those services sign it (with their restaurant claim). */
    private static String serviceToken(String target, String body, KeyPair key, String keyId, String issuer, String subject,
                                       String scope) throws Exception {
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        return "Service " + Jwts.builder().header().keyId(keyId).and().issuer(issuer).subject(subject)
                .audience().add("restofy-qr").and().id(UUID.randomUUID().toString())
                .issuedAt(JwtDates.from(now)).notBefore(JwtDates.from(now)).expiration(JwtDates.from(now.plusSeconds(30)))
                .claim("token_type", "service").claim("scope", scope).claim("restaurant_id", TENANT.toString())
                .claim("method", POST).claim("target", target).claim("body_sha256", digest(body))
                .signWith(key.getPrivate(), Jwts.SIG.RS256).compact();
    }

    private static String digest(String body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
    }

    private record Guest(UUID sessionId, UUID guestId, String token, Instant openedAt) { }
}

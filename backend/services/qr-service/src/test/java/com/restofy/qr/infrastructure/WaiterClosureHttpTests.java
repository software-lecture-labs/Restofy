package com.restofy.qr.infrastructure;

import com.restofy.qr.infrastructure.security.JwtDates;
import com.restofy.qr.support.Q5TestSecuritySupport;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import java.security.KeyPair;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The Waiter close/status routes need the Waiter service credential AND a bound, active staff delegation. */
@ActiveProfiles({"test", "qr-order-inbound"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "qr.payload.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class WaiterClosureHttpTests extends Q5TestSecuritySupport {
    private static final KeyPair ORDER = Jwts.SIG.RS256.keyPair().build();
    private static final KeyPair WAITER = Jwts.SIG.RS256.keyPair().build();
    // Must equal qr.q5.restaurant-id of Q5TestSecuritySupport: the staff token and the service credential share one tenant.
    private static final UUID TENANT = UUID.fromString("6c54daf0-9340-4a1c-981d-f9acc0261779");
    private static final String CLOSE_SCOPE = "qr:session-close";
    private static final String STAFF_HEADER = "X-Staff-Token";
    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;

    @DynamicPropertySource
    static void trust(DynamicPropertyRegistry registry) {
        // The revocation guard only trusts a durable (fsync=on) primary.
        registry.add("spring.datasource.url", () -> "jdbc:tc:q5postgresql:17-alpine:///restofy_test");
        registry.add("AUTH_RESTAURANT_ID", TENANT::toString);
        registry.add("QR_ORDER_CALLER_PUBLIC_KEYS", () -> "{\"order-test\":\"" + encoded(ORDER.getPublic().getEncoded()) + "\"}");
        registry.add("QR_WAITER_CALLER_PUBLIC_KEYS", () -> "{\"waiter-test\":\"" + encoded(WAITER.getPublic().getEncoded()) + "\"}");
    }

    @BeforeEach
    void revocationGuardIsReady() {
        jdbc.update("UPDATE staff_revocation_guard_state SET ready=true,generation=gen_random_uuid(),database_start=pg_postmaster_start_time(),bootstrap_nonce=gen_random_uuid() WHERE singleton=true");
    }

    @Test
    void aDelegatedWaiterReachesTheClosureServiceAsTheRealActor() throws Exception {
        var fixture = fixture();
        UUID user = UUID.randomUUID();
        String staff = staffToken(user, UUID.randomUUID(), "WAITER", TENANT);
        UUID command = UUID.randomUUID();

        // Authorized and bound, so the request reaches the closure service. No Order is connected in this context,
        // so the service honestly reports that the closure evidence is unavailable (the full close flow with a
        // mocked Order is covered by StaffClosurePostgresIntegrationTests).
        var close = close(fixture, command, closeBody(fixture, command, user), staff, bound(staff), CLOSE_SCOPE);
        assertThat(close.statusCode()).isEqualTo(503);
        assertThat(json.readTree(close.body()).get("detail").asString()).contains("closure evidence is unavailable");
        // The status route resolves the delegated actor too: an unknown command of its tenant is a plain 404.
        var status = status(fixture, UUID.randomUUID(), staffToken(user, UUID.randomUUID(), "ADMIN", TENANT));
        assertThat(status.statusCode()).isEqualTo(404);
    }

    @Test
    void theStaffDelegationIsMandatoryBoundAndVerified() throws Exception {
        var fixture = fixture();
        UUID user = UUID.randomUUID();
        UUID sid = UUID.randomUUID();
        String staff = staffToken(user, sid, "WAITER", TENANT);
        String other = staffToken(user, UUID.randomUUID(), "WAITER", TENANT);
        UUID command = UUID.randomUUID();
        String body = closeBody(fixture, command, user);

        assertThat(close(fixture, command, body, null, bound(staff), CLOSE_SCOPE).statusCode()).isEqualTo(401);
        assertThat(close(fixture, command, body, "not-a-jwt", bound("not-a-jwt"), CLOSE_SCOPE).statusCode()).isEqualTo(401);
        // The service credential binds one exact staff token; a swapped or unbound token is refused.
        assertThat(close(fixture, command, body, other, bound(staff), CLOSE_SCOPE).statusCode()).isEqualTo(401);
        assertThat(close(fixture, command, body, staff, null, CLOSE_SCOPE).statusCode()).isEqualTo(401);
        // Only floor roles of the verified tenant may be delegated.
        String kitchen = staffToken(user, UUID.randomUUID(), "KITCHEN", TENANT);
        assertThat(close(fixture, command, body, kitchen, bound(kitchen), CLOSE_SCOPE).statusCode()).isEqualTo(403);
        String foreign = staffToken(user, UUID.randomUUID(), "WAITER", UUID.randomUUID());
        assertThat(close(fixture, command, body, foreign, bound(foreign), CLOSE_SCOPE).statusCode()).isEqualTo(401);
        // The body cannot name another actor than the verified token.
        String impersonated = closeBody(fixture, command, UUID.randomUUID());
        assertThat(close(fixture, command, impersonated, staff, bound(staff), CLOSE_SCOPE).statusCode()).isEqualTo(403);
        // Two staff headers are ambiguous.
        assertThat(close(fixture, command, body, staff + "," + other, bound(staff), CLOSE_SCOPE).statusCode()).isEqualTo(401);
        // A revoked staff session is denied by the local guard.
        jdbc.update("INSERT INTO staff_revoked_sessions(restaurant_id,session_id,user_id,deny_until) VALUES (?,?,?,?)",
                TENANT, sid, user, Timestamp.from(Instant.now().plusSeconds(600)));
        assertThat(close(fixture, command, body, staff, bound(staff), CLOSE_SCOPE).statusCode()).isEqualTo(401);
        assertThat(status(fixture, command, staff).statusCode()).isEqualTo(401);
    }

    @Test
    void otherScopesBrowserCredentialsAndTheOtherCallerCannotUseTheCloseRoutes() throws Exception {
        var fixture = fixture();
        UUID user = UUID.randomUUID();
        String staff = staffToken(user, UUID.randomUUID(), "WAITER", TENANT);
        UUID command = UUID.randomUUID();
        String body = closeBody(fixture, command, user);
        for (String scope : new String[] {"qr:table-read", "qr:guest-validate", "qr:guest-session"}) {
            assertThat(close(fixture, command, body, staff, bound(staff), scope).statusCode()).isEqualTo(403);
        }
        String path = closePath(fixture);
        String orderCredential = service("POST", path, ORDER, "order-test", "restofy-order", "order-service", CLOSE_SCOPE, body, bound(staff));
        assertThat(send("POST", path, body, command, staff, orderCredential).statusCode()).isEqualTo(401);
        // A staff token alone, with no service credential, never closes a session.
        assertThat(send("POST", path, body, command, staff, null).statusCode()).isEqualTo(401);
        // The close scope does not read tables or clean them.
        String tables = "/api/v1/internal/tables";
        assertThat(send("GET", tables, "", null, staff, service("GET", tables, WAITER, "waiter-test", "restofy-waiter", "waiter-service", CLOSE_SCOPE, "", bound(staff))).statusCode()).isEqualTo(403);
    }

    private Fixture fixture() {
        UUID table = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
        jdbc.update("""
                INSERT INTO dining_tables(id, restaurant_id, table_number, operational_status, is_active, created_at, updated_at)
                VALUES (?, ?, ?, 'OCCUPIED', true, ?, ?)
                """, table, TENANT, "W" + table.toString().substring(0, 8), now, now);
        jdbc.update("""
                INSERT INTO table_sessions(id, restaurant_id, table_id, status, opened_at, last_activity_at, idle_expires_at)
                VALUES (?, ?, ?, 'OPEN', ?, ?, ?)
                """, session, TENANT, table, now, now, Timestamp.from(Instant.now().plusSeconds(900)));
        Long version = jdbc.queryForObject("SELECT version FROM table_sessions WHERE id=?", Long.class, session);
        return new Fixture(table, session, version == null ? 0 : version);
    }

    private String closeBody(Fixture fixture, UUID command, UUID requestedBy) {
        return "{\"commandId\":\"" + command + "\",\"restaurantId\":\"" + TENANT + "\",\"tableId\":\"" + fixture.table()
                + "\",\"tableSessionId\":\"" + fixture.session() + "\",\"expectedVersion\":" + fixture.version()
                + ",\"requestedByUserId\":\"" + requestedBy + "\",\"reason\":\"Guests left\",\"requestedAt\":\""
                + Instant.now().truncatedTo(ChronoUnit.SECONDS) + "\"}";
    }

    private static String closePath(Fixture fixture) {
        return "/api/v1/internal/tables/" + fixture.table() + "/sessions/" + fixture.session() + "/close";
    }

    private HttpResponse<String> close(Fixture fixture, UUID command, String body, String staffToken, String staffDigest,
                                       String scope) throws Exception {
        String path = closePath(fixture);
        return send("POST", path, body, command, staffToken,
                service("POST", path, WAITER, "waiter-test", "restofy-waiter", "waiter-service", scope, body, staffDigest));
    }

    private HttpResponse<String> status(Fixture fixture, UUID command, String staffToken) throws Exception {
        String path = "/api/v1/internal/tables/" + fixture.table() + "/sessions/" + fixture.session()
                + "/close-commands/" + command + "?restaurantId=" + TENANT;
        return send("GET", path, "", null, staffToken,
                service("GET", path, WAITER, "waiter-test", "restofy-waiter", "waiter-service", CLOSE_SCOPE, "", bound(staffToken)));
    }

    private HttpResponse<String> send(String method, String target, String body, UUID idempotencyKey, String staffToken,
                                      String credential) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + target))
                .method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (!body.isEmpty()) { request.header("Content-Type", "application/json"); }
        if (idempotencyKey != null) { request.header("Idempotency-Key", idempotencyKey.toString()); }
        if (credential != null) { request.header("Authorization", credential); }
        if (staffToken != null) {
            for (String part : staffToken.split(",")) { request.header(STAFF_HEADER, part); }
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String bound(String token) {
        return token == null ? null : sha256(token);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception _) {
            throw new IllegalStateException();
        }
    }

    private static String service(String method, String target, KeyPair key, String keyId, String issuer, String subject,
                                  String scope, String body, String staffDigest) {
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var builder = Jwts.builder().header().keyId(keyId).and().issuer(issuer).subject(subject)
                .audience().add("restofy-qr").and().id(UUID.randomUUID().toString())
                .issuedAt(JwtDates.from(now)).notBefore(JwtDates.from(now)).expiration(JwtDates.from(now.plusSeconds(30)))
                .claim("token_type", "service").claim("scope", scope).claim("restaurant_id", TENANT.toString())
                .claim("method", method).claim("target", target).claim("body_sha256", sha256(body));
        if (staffDigest != null) { builder.claim("staff_token_sha256", staffDigest); }
        return "Service " + builder.signWith(key.getPrivate(), Jwts.SIG.RS256).compact();
    }

    private static String staffToken(UUID user, UUID sid, String role, UUID restaurant) {
        Instant now = Instant.now();
        return Jwts.builder().issuer("restofy-auth").subject(user.toString()).audience().add("restofy-staff-api").and()
                .issuedAt(Date.from(now)).notBefore(Date.from(now)).expiration(Date.from(now.plusSeconds(300)))
                .id(UUID.randomUUID().toString()).claim("sid", sid.toString()).claim("role", role)
                .claim("restaurant_id", restaurant.toString()).claim("token_type", "access")
                .claim("auth_state", "AUTHENTICATED").claim("auth_policy", "q5-idle-v1")
                .signWith(STAFF.getPrivate(), Jwts.SIG.RS256).compact();
    }

    private record Fixture(UUID table, UUID session, long version) { }
}

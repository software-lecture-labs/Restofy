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
import tools.jackson.databind.JsonNode;
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
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Table cleaning for Waiter: service credential (qr:table-clean) AND a bound staff delegation, idempotent by command ID. */
@ActiveProfiles({"test", "qr-order-inbound"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "qr.payload.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class WaiterTableCleanHttpTests extends Q5TestSecuritySupport {
    private static final KeyPair ORDER = Jwts.SIG.RS256.keyPair().build();
    private static final KeyPair WAITER = Jwts.SIG.RS256.keyPair().build();
    // Must equal qr.q5.restaurant-id of Q5TestSecuritySupport: the staff token and the service credential share one tenant.
    private static final UUID TENANT = UUID.fromString("6c54daf0-9340-4a1c-981d-f9acc0261779");
    private static final String CLEAN_SCOPE = "qr:table-clean";
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
    void aCleaningTableBecomesAvailableOnceAndReplaysAsDuplicate() throws Exception {
        UUID table = table("CLEANING");
        UUID user = UUID.randomUUID();
        String staff = staffToken(user, "WAITER");
        UUID command = UUID.randomUUID();
        String body = body(command, table, 0, user, "2026-11-24T10:00:00Z");

        var applied = clean(table, command, body, staff, CLEAN_SCOPE);
        assertThat(applied.statusCode()).isEqualTo(200);
        var result = json.readTree(applied.body());
        assertThat(result.get("status").asString()).isEqualTo("APPLIED");
        assertThat(result.get("table").get("operationalStatus").asString()).isEqualTo("AVAILABLE");
        assertThat(result.get("table").get("active").asBoolean()).isTrue();
        assertThat(result.get("table").get("currentSession").isNull()).isTrue();
        assertThat(result.get("table").get("version").asLong()).isEqualTo(1);

        var replay = clean(table, command, body, staff, CLEAN_SCOPE);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(json.readTree(replay.body()).get("status").asString()).isEqualTo("DUPLICATE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM table_clean_commands WHERE command_id=?", Integer.class, command)).isEqualTo(1);

        var status = json.readTree(status(table, command, TENANT).body());
        assertThat(status.get("status").asString()).isEqualTo("APPLIED");
        assertThat(status.get("commandId").asString()).isEqualTo(command.toString());

        // The same command ID with a different request is a conflict, never a second cleaning.
        var changed = clean(table, command, body(command, table, 0, user, "2026-11-24T10:00:05Z"), staff, CLEAN_SCOPE);
        assertThat(changed.statusCode()).isEqualTo(409);
        assertThat(json.readTree(changed.body()).get("code").asString()).isEqualTo("COMMAND_CONFLICT");
        // The status answers only for its own table and tenant.
        assertThat(status(table, command, UUID.randomUUID()).statusCode()).isEqualTo(400);
        assertThat(status(table(null), command, TENANT).statusCode()).isEqualTo(404);
        assertThat(status(table, UUID.randomUUID(), TENANT).statusCode()).isEqualTo(404);
    }

    @Test
    void rejectionsAreDefiniteAndDurablyReadable() throws Exception {
        UUID user = UUID.randomUUID();
        String staff = staffToken(user, "ADMIN");

        UUID stale = table("CLEANING");
        assertRejected(stale, 5, user, staff, "TABLE_VERSION_CONFLICT");

        UUID available = table("AVAILABLE");
        assertRejected(available, 0, user, staff, "TABLE_NOT_CLEANING");

        UUID occupied = table("OCCUPIED");
        assertRejected(occupied, 0, user, staff, "TABLE_NOT_CLEANING");

        UUID live = table("CLEANING");
        Timestamp now = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
        jdbc.update("""
                INSERT INTO table_sessions(id, restaurant_id, table_id, status, opened_at, last_activity_at, idle_expires_at, closing_at)
                VALUES (?, ?, ?, 'CLOSING', ?, ?, ?, ?)
                """, UUID.randomUUID(), TENANT, live, now, now, Timestamp.from(Instant.now().plusSeconds(900)), now);
        assertRejected(live, 0, user, staff, "TABLE_SESSION_ACTIVE");
        assertThat(jdbc.queryForObject("SELECT operational_status FROM dining_tables WHERE id=?", String.class, live)).isEqualTo("CLEANING");

        UUID missing = UUID.randomUUID();
        UUID command = UUID.randomUUID();
        var unknown = clean(missing, command, body(command, missing, 0, user, "2026-11-24T10:00:00Z"), staff, CLEAN_SCOPE);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(json.readTree(unknown.body()).get("code").asString()).isEqualTo("TABLE_NOT_FOUND");
    }

    @Test
    void theStaffDelegationAndTheScopeAreMandatory() throws Exception {
        UUID table = table("CLEANING");
        UUID user = UUID.randomUUID();
        String staff = staffToken(user, "WAITER");
        UUID command = UUID.randomUUID();
        String body = body(command, table, 0, user, "2026-11-24T10:00:00Z");

        assertThat(clean(table, command, body, null, CLEAN_SCOPE).statusCode()).isEqualTo(401);
        assertThat(clean(table, command, body, staffToken(user, "KITCHEN"), CLEAN_SCOPE).statusCode()).isEqualTo(403);
        // The body cannot name another actor than the verified token.
        assertThat(clean(table, command, body(command, table, 0, UUID.randomUUID(), "2026-11-24T10:00:00Z"), staff, CLEAN_SCOPE).statusCode()).isEqualTo(403);
        for (String scope : new String[] {"qr:table-read", "qr:session-close", "qr:guest-validate", "qr:guest-session"}) {
            assertThat(clean(table, command, body, staff, scope).statusCode()).isEqualTo(403);
        }
        String path = cleanPath(table);
        String orderCredential = service("POST", path, ORDER, "order-test", "restofy-order", "order-service", CLEAN_SCOPE, body, sha256(staff));
        assertThat(send("POST", path, body, command, staff, orderCredential).statusCode()).isEqualTo(401);
        // The clean scope reads and closes nothing else.
        String tables = "/api/v1/internal/tables";
        assertThat(send("GET", tables, "", null, staff, service("GET", tables, WAITER, "waiter-test", "restofy-waiter", "waiter-service", CLEAN_SCOPE, "", sha256(staff))).statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT operational_status FROM dining_tables WHERE id=?", String.class, table)).isEqualTo("CLEANING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM table_clean_commands WHERE table_id=?", Integer.class, table)).isZero();
    }

    @Test
    void aReplayedPostShowsTheCleanedTableWhileTheStatusShowsTheCurrentOne() throws Exception {
        UUID table = table("CLEANING");
        UUID user = UUID.randomUUID();
        String staff = staffToken(user, "WAITER");
        UUID command = UUID.randomUUID();
        String body = body(command, table, 0, user, "2026-11-24T10:00:00Z");
        assertThat(clean(table, command, body, staff, CLEAN_SCOPE).statusCode()).isEqualTo(200);

        // A guest sits down again after the cleaning.
        jdbc.update("UPDATE dining_tables SET operational_status='OCCUPIED', version=version+1 WHERE id=?", table);

        var replay = json.readTree(clean(table, command, body, staff, CLEAN_SCOPE).body());
        assertThat(replay.get("status").asString()).isEqualTo("DUPLICATE");
        assertThat(replay.get("table").get("operationalStatus").asString()).isEqualTo("AVAILABLE");
        assertThat(replay.get("table").get("version").asLong()).isEqualTo(1);
        assertThat(replay.get("table").get("currentSession").isNull()).isTrue();

        var status = json.readTree(status(table, command, TENANT).body());
        assertThat(status.get("status").asString()).isEqualTo("APPLIED");
        assertThat(status.get("table").get("operationalStatus").asString()).isEqualTo("OCCUPIED");
        assertThat(status.get("table").get("version").asLong()).isEqualTo(2);
    }

    @Test
    void concurrentRequestsCleanTheTableExactlyOnce() throws Exception {
        UUID user = UUID.randomUUID();
        String staff = staffToken(user, "WAITER");

        // The same command sent twice at once: one APPLIED, one DUPLICATE, one durable record, one version bump.
        UUID table = table("CLEANING");
        UUID command = UUID.randomUUID();
        String body = body(command, table, 0, user, "2026-11-24T10:00:00Z");
        var same = race(() -> clean(table, command, body, staff, CLEAN_SCOPE), () -> clean(table, command, body, staff, CLEAN_SCOPE));
        assertThat(same).extracting(HttpResponse::statusCode).containsOnly(200);
        assertThat(same).extracting(response -> readStatus(response.body())).containsExactlyInAnyOrder("APPLIED", "DUPLICATE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM table_clean_commands WHERE table_id=?", Integer.class, table)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM dining_tables WHERE id=?", Long.class, table)).isEqualTo(1L);

        // Two different commands for one table version: one wins, the other is a definite version conflict.
        UUID other = table("CLEANING");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        var different = race(
                () -> clean(other, first, body(first, other, 0, user, "2026-11-24T10:00:00Z"), staff, CLEAN_SCOPE),
                () -> clean(other, second, body(second, other, 0, user, "2026-11-24T10:00:00Z"), staff, CLEAN_SCOPE));
        assertThat(different).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM table_clean_commands WHERE table_id=? AND status='APPLIED'", Integer.class, other)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM table_clean_commands WHERE table_id=? AND status='REJECTED'", Integer.class, other)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM dining_tables WHERE id=?", Long.class, other)).isEqualTo(1L);
    }

    @SafeVarargs
    private static List<HttpResponse<String>> race(Callable<HttpResponse<String>>... calls) throws Exception {
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(calls.length);
        try {
            var futures = new ArrayList<Future<HttpResponse<String>>>();
            for (var call : calls) {
                futures.add(pool.submit(() -> { start.await(); return call.call(); }));
            }
            start.countDown();
            var results = new ArrayList<HttpResponse<String>>();
            for (var future : futures) { results.add(future.get(30, TimeUnit.SECONDS)); }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private String readStatus(String body) {
        return json.readTree(body).get("status").asString();
    }

    private void assertRejected(UUID table, long version, UUID user, String staff, String code) throws Exception {
        UUID command = UUID.randomUUID();
        String body = body(command, table, version, user, "2026-11-24T10:00:00Z");
        var rejected = clean(table, command, body, staff, CLEAN_SCOPE);
        assertThat(rejected.statusCode()).isEqualTo(409);
        assertThat(json.readTree(rejected.body()).get("code").asString()).isEqualTo(code);
        JsonNode status = json.readTree(status(table, command, TENANT).body());
        assertThat(status.get("status").asString()).isEqualTo("REJECTED");
        assertThat(status.get("rejectionCode").asString()).isEqualTo(code);
        // A replay of a rejected command repeats the same definite rejection.
        var replay = clean(table, command, body, staff, CLEAN_SCOPE);
        assertThat(replay.statusCode()).isEqualTo(409);
        assertThat(json.readTree(replay.body()).get("code").asString()).isEqualTo(code);
    }

    private UUID table(String operationalStatus) {
        UUID table = UUID.randomUUID();
        if (operationalStatus == null) {
            operationalStatus = "AVAILABLE";
        }
        Timestamp now = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
        jdbc.update("""
                INSERT INTO dining_tables(id, restaurant_id, table_number, operational_status, is_active, created_at, updated_at)
                VALUES (?, ?, ?, ?, true, ?, ?)
                """, table, TENANT, "C" + table.toString().substring(0, 8), operationalStatus, now, now);
        return table;
    }

    private static String body(UUID command, UUID table, long version, UUID requestedBy, String requestedAt) {
        return "{\"commandId\":\"" + command + "\",\"restaurantId\":\"" + TENANT + "\",\"tableId\":\"" + table
                + "\",\"expectedVersion\":" + version + ",\"requestedByUserId\":\"" + requestedBy
                + "\",\"requestedAt\":\"" + requestedAt + "\"}";
    }

    private static String cleanPath(UUID table) {
        return "/api/v1/internal/tables/" + table + "/cleaned";
    }

    private HttpResponse<String> clean(UUID table, UUID command, String body, String staffToken, String scope) throws Exception {
        String path = cleanPath(table);
        return send("POST", path, body, command, staffToken,
                service("POST", path, WAITER, "waiter-test", "restofy-waiter", "waiter-service", scope, body,
                        staffToken == null ? null : sha256(staffToken)));
    }

    /** The status read carries the Waiter service credential only: no staff token, no digest. */
    private HttpResponse<String> status(UUID table, UUID command, UUID restaurant) throws Exception {
        return status(table, command, restaurant, CLEAN_SCOPE);
    }

    private HttpResponse<String> status(UUID table, UUID command, UUID restaurant, String scope) throws Exception {
        String path = "/api/v1/internal/tables/" + table + "/clean-commands/" + command + "?restaurantId=" + restaurant;
        return send("GET", path, "", null, null,
                service("GET", path, WAITER, "waiter-test", "restofy-waiter", "waiter-service", scope, "", null));
    }

    @Test
    void theStatusReadNeedsOnlyTheWaiterServiceCredentialWithTheCleanScope() throws Exception {
        UUID table = table(null);
        UUID command = UUID.randomUUID();
        assertThat(status(table, command, TENANT).statusCode()).isEqualTo(404);
        assertThat(status(table, command, TENANT, "qr:table-read").statusCode()).isEqualTo(403);
        assertThat(status(table, command, TENANT, "qr:session-close").statusCode()).isEqualTo(403);
        String path = "/api/v1/internal/tables/" + table + "/clean-commands/" + command + "?restaurantId=" + TENANT;
        String order = service("GET", path, ORDER, "order-test", "restofy-order", "order-service", CLEAN_SCOPE, "", null);
        assertThat(send("GET", path, "", null, null, order).statusCode()).isEqualTo(401);
        assertThat(send("GET", path, "", null, null, null).statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> send(String method, String target, String body, UUID idempotencyKey, String staffToken,
                                      String credential) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + target))
                .method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (!body.isEmpty()) { request.header("Content-Type", "application/json"); }
        if (idempotencyKey != null) { request.header("Idempotency-Key", idempotencyKey.toString()); }
        if (credential != null) { request.header("Authorization", credential); }
        if (staffToken != null) { request.header(STAFF_HEADER, staffToken); }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
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

    private static String staffToken(UUID user, String role) {
        Instant now = Instant.now();
        return Jwts.builder().issuer("restofy-auth").subject(user.toString()).audience().add("restofy-staff-api").and()
                .issuedAt(Date.from(now)).notBefore(Date.from(now)).expiration(Date.from(now.plusSeconds(300)))
                .id(UUID.randomUUID().toString()).claim("sid", UUID.randomUUID().toString()).claim("role", role)
                .claim("restaurant_id", TENANT.toString()).claim("token_type", "access")
                .claim("auth_state", "AUTHENTICATED").claim("auth_policy", "q5-idle-v1")
                .signWith(STAFF.getPrivate(), Jwts.SIG.RS256).compact();
    }
}

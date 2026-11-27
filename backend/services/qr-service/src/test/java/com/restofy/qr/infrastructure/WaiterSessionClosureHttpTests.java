package com.restofy.qr.infrastructure;

import com.restofy.qr.infrastructure.security.JwtDates;
import com.restofy.qr.support.Q5TestSecuritySupport;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
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
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Durable closure events for Waiter: written by the database with the final transition, read by a signed cursor call. */
@ActiveProfiles({"test", "qr-order-inbound"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "qr.payload.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class WaiterSessionClosureHttpTests extends Q5TestSecuritySupport {
    private static final KeyPair ORDER = Jwts.SIG.RS256.keyPair().build();
    private static final KeyPair WAITER = Jwts.SIG.RS256.keyPair().build();
    // Must equal qr.q5.restaurant-id of Q5TestSecuritySupport.
    private static final UUID TENANT = UUID.fromString("6c54daf0-9340-4a1c-981d-f9acc0261779");
    private static final String READ_SCOPE = "qr:session-closure-read";
    private static final String PATH = "/api/v1/internal/session-closures";
    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired DataSource dataSource;

    @DynamicPropertySource
    static void trust(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:tc:q5postgresql:17-alpine:///restofy_test");
        registry.add("AUTH_RESTAURANT_ID", TENANT::toString);
        registry.add("QR_ORDER_CALLER_PUBLIC_KEYS", () -> "{\"order-test\":\"" + encoded(ORDER.getPublic().getEncoded()) + "\"}");
        registry.add("QR_WAITER_CALLER_PUBLIC_KEYS", () -> "{\"waiter-test\":\"" + encoded(WAITER.getPublic().getEncoded()) + "\"}");
    }

    private record Seeded(long before, UUID table, UUID closed, UUID expiredTable, UUID expired, UUID stillOpen) { }

    /** One session expired, one CLOSING and one still live; the closing one is not final yet. */
    private Seeded seedFinalTransitions() throws Exception {
        long before = cursorNow();
        UUID table = table(TENANT);
        UUID closed = session(table, TENANT);
        UUID expiredTable = table(TENANT);
        UUID expired = session(expiredTable, TENANT);
        UUID stillOpen = session(table(TENANT), TENANT);
        jdbc.update("UPDATE table_sessions SET status='EXPIRED', closed_at=now() WHERE id=?", expired);
        jdbc.update("UPDATE table_sessions SET status='CLOSING', closing_at=now() WHERE id=?", closed);
        return new Seeded(before, table, closed, expiredTable, expired, stillOpen);
    }

    @Test
    void everyFinalTransitionLeavesOneDurableEventReadByCursor() throws Exception {
        var seeded = seedFinalTransitions();
        assertThat(read(seeded.before(), 100).get("items").size()).isEqualTo(1);
        jdbc.update("UPDATE table_sessions SET status='CLOSED', closed_at=now() WHERE id=?", seeded.closed());

        var items = read(seeded.before(), 100).get("items");
        assertThat(items.size()).isEqualTo(2);
        assertThat(items.get(0).get("tableSessionId").asString()).isEqualTo(seeded.expired().toString());
        assertThat(items.get(0).get("tableId").asString()).isEqualTo(seeded.expiredTable().toString());
        assertThat(items.get(0).get("finalStatus").asString()).isEqualTo("EXPIRED");
        assertThat(items.get(1).get("tableSessionId").asString()).isEqualTo(seeded.closed().toString());
        assertThat(items.get(1).get("finalStatus").asString()).isEqualTo("CLOSED");
        assertThat(items.get(1).get("tableId").asString()).isEqualTo(seeded.table().toString());
        for (var item : items) {
            assertThat(item.get("restaurantId").asString()).isEqualTo(TENANT.toString());
            assertThat(UUID.fromString(item.get("eventId").asString())).isNotNull();
            assertThat(item.get("occurredAt").asString()).isNotBlank();
        }
        assertThat(items.get(0).has("guestSessionId")).isFalse();
    }

    @Test
    void theCursorIsReplaySafePagedAndNeverDuplicatesAnEvent() throws Exception {
        var seeded = seedFinalTransitions();
        jdbc.update("UPDATE table_sessions SET status='CLOSED', closed_at=now() WHERE id=?", seeded.closed());
        var page = read(seeded.before(), 100);
        var items = page.get("items");
        long next = page.get("nextCursor").asLong();
        assertThat(next).isGreaterThan(seeded.before());

        // Nothing new: the same cursor comes back. A re-read from the old cursor returns the same events (replay-safe).
        var empty = read(next, 100);
        assertThat(empty.get("items").size()).isZero();
        assertThat(empty.get("nextCursor").asLong()).isEqualTo(next);
        assertThat(read(seeded.before(), 100).get("items").get(0).get("eventId").asString())
                .isEqualTo(items.get(0).get("eventId").asString());

        // The page size bounds a read and the cursor continues after it.
        var first = read(seeded.before(), 1);
        assertThat(first.get("items").size()).isEqualTo(1);
        var second = read(first.get("nextCursor").asLong(), 1);
        assertThat(second.get("items").get(0).get("tableSessionId").asString()).isEqualTo(seeded.closed().toString());

        // A session that is still live, or that only changes other columns later, never adds a second event.
        jdbc.update("UPDATE table_sessions SET version=version+1 WHERE id=?", seeded.closed());
        assertThat(read(seeded.before(), 100).get("items").size()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM table_session_closure_events WHERE table_session_id=?",
                Integer.class, seeded.stillOpen())).isZero();
    }

    @Test
    void anotherRestaurantsEventsAreNeverVisibleAndEventsAreImmutable() throws Exception {
        long before = cursorNow();
        UUID otherTenant = UUID.randomUUID();
        UUID otherTable = table(otherTenant);
        UUID otherSession = session(otherTable, otherTenant);
        jdbc.update("UPDATE table_sessions SET status='EXPIRED', closed_at=now() WHERE id=?", otherSession);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM table_session_closure_events WHERE table_session_id=?",
                Integer.class, otherSession)).isEqualTo(1);
        assertThat(read(before, 100).get("items").size()).isZero();

        assertThatThrownBy(() -> jdbc.update("UPDATE table_session_closure_events SET final_status='CLOSED' WHERE table_session_id=?", otherSession))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM table_session_closure_events WHERE table_session_id=?", otherSession))
                .isInstanceOf(DataAccessException.class);
    }

    /**
     * A later closure of the same restaurant must not commit before an earlier, still open one: otherwise the reader
     * moves its cursor past the earlier event_seq and that event is lost when it commits.
     */
    @Test
    void aLaterClosureWaitsForTheEarlierOneSoTheCursorCannotSkipAnEvent() throws Exception {
        long before = cursorNow();
        UUID first = session(table(TENANT), TENANT);
        UUID second = session(table(TENANT), TENANT);
        UUID otherTenant = UUID.randomUUID();
        UUID other = session(table(otherTenant), otherTenant);

        // The connection closes first (rolling back and releasing the lock) so a failed assertion cannot hang the executor.
        try (ExecutorService executor = Executors.newSingleThreadExecutor(); Connection earlier = dataSource.getConnection()) {
            earlier.setAutoCommit(false);
            close(earlier, first);

            // Another restaurant is not serialized behind it.
            executor.submit(() -> closeAndCommit(other)).get(10, TimeUnit.SECONDS);

            // The same restaurant waits for the open transaction instead of committing a higher event_seq first.
            Future<?> later = executor.submit(() -> closeAndCommit(second));
            assertThatThrownBy(() -> later.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(read(before, 100).get("items").size()).isZero();

            earlier.commit();
            later.get(10, TimeUnit.SECONDS);
        }

        var items = read(before, 100).get("items");
        assertThat(items.size()).isEqualTo(2);
        assertThat(items.get(0).get("tableSessionId").asString()).isEqualTo(first.toString());
        assertThat(items.get(1).get("tableSessionId").asString()).isEqualTo(second.toString());
    }

    @Test
    void onlyTheWaiterReadScopeMayReadAndInputsAreBounded() throws Exception {
        assertThat(get(PATH, READ_SCOPE, WAITER, "waiter-test", "restofy-waiter", "waiter-service").statusCode()).isEqualTo(200);
        for (String scope : new String[] {"qr:table-read", "qr:table-clean", "qr:session-close", "qr:guest-validate", "qr:guest-session"}) {
            // The close and clean scopes also demand a staff delegation, which the filter answers first (401).
            assertThat(get(PATH, scope, WAITER, "waiter-test", "restofy-waiter", "waiter-service").statusCode()).isIn(401, 403);
        }
        assertThat(get(PATH, READ_SCOPE, ORDER, "order-test", "restofy-order", "order-service").statusCode()).isEqualTo(401);
        // The read scope opens nothing else.
        assertThat(get("/api/v1/internal/tables", READ_SCOPE, WAITER, "waiter-test", "restofy-waiter", "waiter-service").statusCode()).isEqualTo(403);
        assertThat(get(PATH + "?restaurantId=" + UUID.randomUUID(), READ_SCOPE, WAITER, "waiter-test", "restofy-waiter", "waiter-service").statusCode()).isEqualTo(400);
        assertThat(get(PATH + "?limit=0", READ_SCOPE, WAITER, "waiter-test", "restofy-waiter", "waiter-service").statusCode()).isEqualTo(400);
        assertThat(get(PATH + "?limit=201", READ_SCOPE, WAITER, "waiter-test", "restofy-waiter", "waiter-service").statusCode()).isEqualTo(400);
        assertThat(get(PATH + "?cursor=-1", READ_SCOPE, WAITER, "waiter-test", "restofy-waiter", "waiter-service").statusCode()).isEqualTo(400);
        assertThat(rawGet(PATH, null).statusCode()).isEqualTo(401);
    }

    private static void close(Connection connection, UUID session) throws SQLException {
        try (var statement = connection.prepareStatement("UPDATE table_sessions SET status='CLOSED', closed_at=now() WHERE id=?")) {
            statement.setObject(1, session);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private Void closeAndCommit(UUID session) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            close(connection, session);
            connection.commit();
        }
        return null;
    }

    private long cursorNow() {
        Long max = jdbc.queryForObject("SELECT COALESCE(max(event_seq), 0) FROM table_session_closure_events", Long.class);
        return max == null ? 0 : max;
    }

    private tools.jackson.databind.JsonNode read(long cursor, int limit) throws Exception {
        var response = get(PATH + "?restaurantId=" + TENANT + "&cursor=" + cursor + "&limit=" + limit, READ_SCOPE, WAITER,
                "waiter-test", "restofy-waiter", "waiter-service");
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private UUID table(UUID tenant) {
        UUID table = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
        jdbc.update("""
                INSERT INTO dining_tables(id, restaurant_id, table_number, operational_status, is_active, created_at, updated_at)
                VALUES (?, ?, ?, 'AVAILABLE', true, ?, ?)
                """, table, tenant, "E" + table.toString().substring(0, 8), now, now);
        return table;
    }

    /** An OPEN session (a table holds one live session, so every fixture session gets its own table). */
    private UUID session(UUID table, UUID tenant) {
        UUID id = UUID.randomUUID();
        Timestamp opened = Timestamp.from(Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS));
        jdbc.update("""
                INSERT INTO table_sessions(id, restaurant_id, table_id, status, opened_at, last_activity_at, idle_expires_at)
                VALUES (?, ?, ?, 'OPEN', ?, ?, ?)
                """, id, tenant, table, opened, opened, Timestamp.from(Instant.now().plusSeconds(900)));
        return id;
    }

    private HttpResponse<String> get(String target, String scope, KeyPair key, String keyId, String issuer, String subject) throws Exception {
        return rawGet(target, service(target, key, keyId, issuer, subject, scope));
    }

    private HttpResponse<String> rawGet(String target, String credential) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + target)).GET();
        if (credential != null) { request.header("Authorization", credential); }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception _) {
            throw new IllegalStateException();
        }
    }

    private static String service(String target, KeyPair key, String keyId, String issuer, String subject, String scope) {
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        return "Service " + Jwts.builder().header().keyId(keyId).and().issuer(issuer).subject(subject)
                .audience().add("restofy-qr").and().id(UUID.randomUUID().toString())
                .issuedAt(JwtDates.from(now)).notBefore(JwtDates.from(now)).expiration(JwtDates.from(now.plusSeconds(30)))
                .claim("token_type", "service").claim("scope", scope).claim("restaurant_id", TENANT.toString())
                .claim("method", "GET").claim("target", target).claim("body_sha256", sha256(""))
                .signWith(key.getPrivate(), Jwts.SIG.RS256).compact();
    }
}

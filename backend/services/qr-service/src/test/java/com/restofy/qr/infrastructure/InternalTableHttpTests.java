package com.restofy.qr.infrastructure;

import com.restofy.qr.application.port.OrderSessionAccess;
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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles({"test", "qr-order-inbound"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "qr.payload.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class InternalTableHttpTests extends Q5TestSecuritySupport {
    private static final KeyPair ORDER = Jwts.SIG.RS256.keyPair().build();
    private static final KeyPair WAITER = Jwts.SIG.RS256.keyPair().build();
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final String TABLES = "/api/v1/internal/tables";
    private static final String TABLE_READ = "qr:table-read";
    private static final String GUEST_VALIDATE = "qr:guest-validate";
    private static final String GET = "GET";
    private static final String CODE = "code";
    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @MockitoBean OrderSessionAccess orders;

    @DynamicPropertySource
    static void trust(DynamicPropertyRegistry registry) {
        registry.add("AUTH_RESTAURANT_ID", TENANT::toString);
        registry.add("QR_ORDER_CALLER_PUBLIC_KEYS", () -> "{\"order-test\":\"" + encoded(ORDER.getPublic().getEncoded()) + "\"}");
        registry.add("QR_WAITER_CALLER_PUBLIC_KEYS", () -> "{\"waiter-test\":\"" + encoded(WAITER.getPublic().getEncoded()) + "\"}");
    }

    @Test
    void listIsPagedStableTenantScopedAndCarriesTheLiveSession() throws Exception {
        List<UUID> expected = new ArrayList<>();
        for (int index = 0; index < 201; index++) {
            expected.add(table(TENANT, "T" + index, "AVAILABLE", true));
        }
        UUID foreign = table(OTHER_TENANT, "FOREIGN", "AVAILABLE", true);
        UUID occupied = table(TENANT, "OCC", "OCCUPIED", true);
        expected.add(occupied);
        UUID session = liveSession(TENANT, occupied, "OPEN");
        scans(TENANT, occupied, session, 3);
        UUID oldTable = table(TENANT, "OLD", "DISABLED", false);
        expected.add(oldTable);
        closedSession(TENANT, oldTable);

        var first = waiter(GET, TABLES + "?restaurantId=" + TENANT + "&page=0&size=200");
        var second = waiter(GET, TABLES + "?restaurantId=" + TENANT + "&page=1&size=200");
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        JsonNode firstPage = json.readTree(first.body());
        JsonNode secondPage = json.readTree(second.body());
        assertThat(firstPage.get("items")).hasSize(200);
        assertThat(secondPage.get("items")).hasSize(3);
        assertThat(firstPage.get("totalElements").asLong()).isEqualTo(203);
        assertThat(firstPage.get("page").asInt()).isZero();
        assertThat(firstPage.get("size").asInt()).isEqualTo(200);
        var seen = new HashSet<String>();
        String previous = "";
        for (JsonNode page : List.of(firstPage, secondPage)) {
            for (JsonNode item : page.get("items")) {
                String id = item.get("id").asString();
                assertThat(id).isGreaterThan(previous);
                previous = id;
                assertThat(seen.add(id)).isTrue();
                assertThat(item.get("restaurantId").asString()).isEqualTo(TENANT.toString());
            }
        }
        assertThat(seen).containsExactlyInAnyOrderElementsOf(expected.stream().map(UUID::toString).toList());
        assertThat(seen).doesNotContain(foreign.toString());

        JsonNode live = find(firstPage, secondPage, occupied);
        assertThat(live.get("operationalStatus").asString()).isEqualTo("OCCUPIED");
        assertThat(live.get("currentSession").get("id").asString()).isEqualTo(session.toString());
        assertThat(live.get("currentSession").get("status").asString()).isEqualTo("OPEN");
        assertThat(live.get("currentSession").get("scanCount").asLong()).isEqualTo(3);
        assertThat(live.get("currentSession").get("version").isNumber()).isTrue();
        JsonNode retired = find(firstPage, secondPage, oldTable);
        assertThat(retired.get("active").asBoolean()).isFalse();
        assertThat(hasNoSession(retired)).isTrue();
    }

    @Test
    void detailShowsTheLiveSessionAndIsUniform404ForUnknownOrForeignTables() throws Exception {
        UUID table = table(TENANT, "D1", "OCCUPIED", true);
        UUID session = liveSession(TENANT, table, "CLOSING");
        scans(TENANT, table, session, 2);
        UUID idle = table(TENANT, "D2", "AVAILABLE", true);
        UUID foreign = table(OTHER_TENANT, "D3", "AVAILABLE", true);

        var found = json.readTree(waiter(GET, TABLES + "/" + table).body());
        assertThat(found.get("id").asString()).isEqualTo(table.toString());
        assertThat(found.get("currentSession").get("status").asString()).isEqualTo("CLOSING");
        assertThat(found.get("currentSession").get("scanCount").asLong()).isEqualTo(2);
        var free = json.readTree(waiter(GET, TABLES + "/" + idle + "?restaurantId=" + TENANT).body());
        assertThat(free.get("operationalStatus").asString()).isEqualTo("AVAILABLE");
        assertThat(hasNoSession(free)).isTrue();

        for (UUID missing : List.of(UUID.randomUUID(), foreign)) {
            var response = waiter(GET, TABLES + "/" + missing);
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(json.readTree(response.body()).has(CODE)).isFalse();
        }
    }

    @Test
    void onlyTheWaiterTableReadScopeMayReadAndOnlyForItsOwnRestaurant() throws Exception {
        UUID table = table(TENANT, "S1", "AVAILABLE", true);
        String detail = TABLES + "/" + table;
        assertThat(waiter(GET, TABLES).statusCode()).isEqualTo(200);
        // Another scope of the same caller, and the other caller, never read tables.
        assertThat(send(GET, detail, sign(GET, detail, WAITER, "waiter-test", "restofy-waiter", "waiter-service", GUEST_VALIDATE, TENANT)).statusCode()).isEqualTo(403);
        assertThat(send(GET, detail, sign(GET, detail, ORDER, "order-test", "restofy-order", "order-service", "qr:guest-session", TENANT)).statusCode()).isEqualTo(403);
        assertThat(send(GET, detail, sign(GET, detail, ORDER, "order-test", "restofy-order", "order-service", TABLE_READ, TENANT)).statusCode()).isEqualTo(403);
        // A token for another restaurant, no credential, a browser cookie and a staff key are all refused.
        assertThat(send(GET, detail, sign(GET, detail, WAITER, "waiter-test", "restofy-waiter", "waiter-service", TABLE_READ, OTHER_TENANT)).statusCode()).isEqualTo(403);
        assertThat(send(GET, detail, null).statusCode()).isEqualTo(401);
        assertThat(send(GET, detail, sign(GET, detail, STAFF, "waiter-test", "restofy-waiter", "waiter-service", TABLE_READ, TENANT)).statusCode()).isEqualTo(401);
        // The restaurant in the query cannot widen the verified tenant.
        assertThat(waiter(GET, TABLES + "?restaurantId=" + OTHER_TENANT).statusCode()).isEqualTo(400);
        assertThat(waiter(GET, detail + "?restaurantId=" + OTHER_TENANT).statusCode()).isEqualTo(400);
        // The page size is bounded.
        assertThat(waiter(GET, TABLES + "?size=201").statusCode()).isEqualTo(400);
        assertThat(waiter(GET, TABLES + "?size=0").statusCode()).isEqualTo(400);
        assertThat(waiter(GET, TABLES + "?page=-1").statusCode()).isEqualTo(400);
    }

    @Test
    void tableCommandsAndOtherTableRoutesStayClosedToTheServiceCredential() throws Exception {
        UUID table = table(TENANT, "C1", "OCCUPIED", true);
        UUID session = liveSession(TENANT, table, "OPEN");
        String close = TABLES + "/" + table + "/sessions/" + session + "/close";
        assertThat(sendWithBody("POST", close, "{}").statusCode()).isIn(401, 403);
        assertThat(sendWithBody("POST", TABLES + "/" + table + "/cleaned", "{}").statusCode()).isIn(401, 403);
        assertThat(sendWithBody("POST", TABLES, "{}").statusCode()).isIn(401, 403);
        assertThat(sendWithBody("DELETE", TABLES + "/" + table, "").statusCode()).isIn(401, 403);
    }

    private static boolean hasNoSession(JsonNode table) {
        return table.get("currentSession") == null || table.get("currentSession").isNull();
    }

    private JsonNode find(JsonNode first, JsonNode second, UUID id) {
        for (JsonNode page : List.of(first, second)) {
            for (JsonNode item : page.get("items")) {
                if (item.get("id").asString().equals(id.toString())) { return item; }
            }
        }
        throw new AssertionError("Table " + id + " is missing from the list");
    }

    private UUID table(UUID tenant, String number, String status, boolean active) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
        jdbc.update("""
                INSERT INTO dining_tables(id, restaurant_id, table_number, operational_status, is_active, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, tenant, number, status, active, now, now);
        return id;
    }

    private UUID liveSession(UUID tenant, UUID table, String status) {
        UUID id = UUID.randomUUID();
        Instant opened = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        Timestamp openedAt = Timestamp.from(opened);
        Timestamp closing = "CLOSING".equals(status) ? Timestamp.from(opened.plusSeconds(30)) : null;
        jdbc.update("""
                INSERT INTO table_sessions(id, restaurant_id, table_id, status, opened_at, last_activity_at, idle_expires_at, closing_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, tenant, table, status, openedAt, openedAt, Timestamp.from(opened.plusSeconds(900)), closing);
        return id;
    }

    private void closedSession(UUID tenant, UUID table) {
        Instant opened = Instant.now().minusSeconds(600).truncatedTo(ChronoUnit.MICROS);
        jdbc.update("""
                INSERT INTO table_sessions(id, restaurant_id, table_id, status, opened_at, last_activity_at, closed_at)
                VALUES (?, ?, ?, 'CLOSED', ?, ?, ?)
                """, UUID.randomUUID(), tenant, table, Timestamp.from(opened), Timestamp.from(opened), Timestamp.from(opened.plusSeconds(60)));
    }

    private void scans(UUID tenant, UUID table, UUID session, int count) {
        UUID qr = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
        jdbc.update("""
                INSERT INTO qr_codes(id, restaurant_id, table_id, token_digest, encrypted_payload, status, issued_at)
                VALUES (?, ?, ?, ?, ?, 'INACTIVE', ?)
                """, qr, tenant, table, UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""), "v1:" + "A".repeat(95), now);
        for (int index = 0; index < count; index++) {
            UUID guest = UUID.randomUUID();
            Timestamp expires = Timestamp.from(Instant.now().plusSeconds(900).truncatedTo(ChronoUnit.MICROS));
            jdbc.update("""
                    INSERT INTO guest_sessions(id, restaurant_id, table_session_id, token_digest, status, joined_at, last_activity_at, expires_at)
                    VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
                    """, guest, tenant, session, UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""),
                    now, now, expires);
            jdbc.update("""
                    INSERT INTO qr_scan_events(id, restaurant_id, table_id, qr_code_id, table_session_id, guest_session_id, scanned_at, outcome)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'ACCEPTED')
                    """, UUID.randomUUID(), tenant, table, qr, session, guest, now);
        }
    }

    private HttpResponse<String> waiter(String method, String target) throws Exception {
        return send(method, target, sign(method, target, WAITER, "waiter-test", "restofy-waiter", "waiter-service", TABLE_READ, TENANT));
    }

    private HttpResponse<String> sendWithBody(String method, String target, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + target))
                .method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .header("Authorization", sign(method, target, WAITER, "waiter-test", "restofy-waiter", "waiter-service", TABLE_READ, TENANT, body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String method, String target, String credential) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + target)).method(method, HttpRequest.BodyPublishers.noBody());
        if (credential != null) { request.header("Authorization", credential); }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String sign(String method, String target, KeyPair key, String keyId, String issuer, String subject,
                               String scope, UUID tenant) throws Exception {
        return sign(method, target, key, keyId, issuer, subject, scope, tenant, "");
    }

    private static String sign(String method, String target, KeyPair key, String keyId, String issuer, String subject,
                               String scope, UUID tenant, String body) throws Exception {
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        return "Service " + Jwts.builder().header().keyId(keyId).and().issuer(issuer).subject(subject)
                .audience().add("restofy-qr").and().id(UUID.randomUUID().toString())
                .issuedAt(JwtDates.from(now)).notBefore(JwtDates.from(now)).expiration(JwtDates.from(now.plusSeconds(30)))
                .claim("token_type", "service").claim("scope", scope).claim("restaurant_id", tenant.toString())
                .claim("method", method).claim("target", target).claim("body_sha256", digest)
                .signWith(key.getPrivate(), Jwts.SIG.RS256).compact();
    }
}

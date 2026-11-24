package com.restofy.waiter.support;

import com.restofy.waiter.domain.enums.StaffRole;
import com.restofy.waiter.infrastructure.security.AuthenticatedStaff;
import com.restofy.waiter.infrastructure.security.TestStaffCredentials;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import jakarta.servlet.http.Cookie;
import org.springframework.mock.web.MockHttpServletRequest;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Full-context API test base: the real security filter chain, controllers, services, REST clients and a
 * real PostgreSQL (Testcontainers). Only the QR, Order and Notification services are replaced, by a local HTTP
 * stub of the DRAFT contracts. The authentication helpers fill the SecurityContext with the verified-staff
 * principal that the future Auth integration will provide; nothing in the production chain is relaxed. Guests
 * authenticate the way production does: with a credential that the QR stub verifies.
 *
 * <p>The background triggers (Notification delivery, table command reconciliation) are switched off here so
 * tests drive their rounds deterministically.
 */
@SpringBootTest(properties = {"restofy.notification.dispatcher.enabled=false",
        "restofy.table-commands.reconciler.enabled=false"})
@AutoConfigureMockMvc
public abstract class ApiTestBase {

    protected static final DownstreamStub STUB = DownstreamStub.start();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(STUB::close));
    }

    protected static final UUID RESTAURANT = UUID.fromString("00000000-0000-0000-0000-00000000a001");
    /** The only browser Origin the tests allow; any other Origin must fail the CSRF/Origin proof. */
    protected static final String ALLOWED_ORIGIN = "https://app.restofy.test";
    protected static final UUID OTHER_RESTAURANT = UUID.fromString("00000000-0000-0000-0000-00000000b002");

    @DynamicPropertySource
    static void downstreams(DynamicPropertyRegistry registry) {
        registry.add("restofy.services.qr-url", () -> STUB.baseUrl().toString());
        registry.add("restofy.services.order-url", () -> STUB.baseUrl().toString());
        registry.add("restofy.services.notification-url", () -> STUB.baseUrl().toString());
        registry.add("restofy.rest-client.read-timeout-ms", () -> "700");
        registry.add("restofy.auth.allowed-origins", () -> ALLOWED_ORIGIN);
        registry.add("WAITER_ORDER_CALLER_KEY_ID", () -> FakeBackends.ORDER_CALLER_KEY_ID);
        registry.add("WAITER_ORDER_CALLER_PRIVATE_KEY", FakeBackends::orderCallerPrivateKey);
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    protected FakeBackends backends;

    protected FakeNotification notification;

    @BeforeEach
    void resetDownstreams() {
        STUB.reset();
        backends = new FakeBackends().installOn(STUB);
        notification = new FakeNotification().installOn(STUB);
        cleanTables();
    }

    @AfterEach
    void cleanDatabase() {
        cleanTables();
    }

    private void cleanTables() {
        jdbc.update("DELETE FROM order_serve_commands");
        jdbc.update("DELETE FROM idempotency_records");
        jdbc.update("DELETE FROM notification_deliveries");
        jdbc.update("DELETE FROM service_call_events");
        jdbc.update("DELETE FROM service_requests");
        jdbc.update("DELETE FROM table_commands");
    }

    protected static RequestPostProcessor asStaff(StaffRole role, UUID restaurantId) {
        return as(new AuthenticatedStaff(UUID.randomUUID(), restaurantId, role), role.name());
    }

    protected static RequestPostProcessor asWaiter() {
        return asStaff(StaffRole.WAITER, RESTAURANT);
    }

    /** A specific, verified waiter: tests that care about who claimed or resolved a call. */
    protected static RequestPostProcessor asWaiter(UUID staffUserId) {
        return as(new AuthenticatedStaff(staffUserId, RESTAURANT, StaffRole.WAITER), StaffRole.WAITER.name());
    }

    protected static RequestPostProcessor asWaiterOf(UUID restaurantId) {
        return asStaff(StaffRole.WAITER, restaurantId);
    }

    /** The authority is passed separately so tests can build mismatches between authority and principal. */
    protected static RequestPostProcessor as(Object principal, String... authorities) {
        List<SimpleGrantedAuthority> granted = Arrays.stream(authorities)
                .map(authority -> new SimpleGrantedAuthority("ROLE_" + authority)).toList();
        RequestPostProcessor authenticated = authentication(new UsernamePasswordAuthenticationToken(principal, null, granted));
        return request -> {
            if (principal instanceof AuthenticatedStaff staff) {
                // What the staff cookie filter keeps in production, so Waiter can act for this staff member at Order.
                TestStaffCredentials.attach(request, TestStaffCredentials.credentialOf(staff.restaurantId()));
            }
            return authenticated.postProcessRequest(request);
        };
    }

    protected FakeBackends.Table addTable(UUID restaurantId, String number, String status, UUID sessionId) {
        FakeBackends.Table table = new FakeBackends.Table(UUID.randomUUID(), restaurantId, number, status, sessionId);
        backends.tables.add(table);
        return table;
    }

    protected FakeBackends.Table addTable(String number, String status, UUID sessionId) {
        return addTable(RESTAURANT, number, status, sessionId);
    }

    /** {@code preparation} is null, WAITING, PREPARING or READY; a READY order gets a ready time after {@code acceptedAt}. */
    protected FakeBackends.Order addOrder(FakeBackends.Table table, UUID sessionId, String number, String business,
                                          String delivery, String preparation, String acceptedAt) {
        Instant accepted = Instant.parse(acceptedAt);
        Instant ready = "READY".equals(preparation) ? accepted.plusSeconds(600) : null;
        Instant served = "SERVED".equals(delivery) ? accepted.plusSeconds(900) : null;
        FakeBackends.Order order = new FakeBackends.Order(UUID.randomUUID(), table.restaurantId(), table.id(), sessionId,
                number, business, delivery, preparation, accepted, ready, served);
        backends.orders.add(order);
        return order;
    }

    /** Registers a guest credential at QR for the table's live session: an open session and an active guest. */
    protected FakeBackends.Guest addGuest(FakeBackends.Table table) {
        FakeBackends.Guest guest = new FakeBackends.Guest(randomProof(), table.restaurantId(),
                table.id(), table.sessionId(), UUID.randomUUID(), "OPEN", "ACTIVE");
        backends.guests.put(guest.token(), guest);
        return guest;
    }

    /** Inserts a call row that satisfies the DB invariants of its status; returns the call id. */
    protected UUID insertServiceRequest(UUID restaurantId, UUID sessionId, String type, String status) {
        return insertServiceRequest(restaurantId, UUID.randomUUID(), sessionId, UUID.randomUUID(), type, status,
                Instant.now());
    }

    protected UUID insertServiceRequest(UUID restaurantId, UUID tableId, UUID sessionId, UUID guestSessionId,
                                        String type, String status, Instant createdAt) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(createdAt);
        boolean claimed = "CLAIMED".equals(status);
        boolean resolved = "RESOLVED".equals(status);
        boolean cancelled = "CANCELLED".equals(status);
        jdbc.update("""
                INSERT INTO service_requests (id, restaurant_id, table_id, table_session_id, guest_session_id, request_type,
                                              message, status, claimed_by_user_id, claimed_at, resolved_by_user_id, resolved_at,
                                              closure_reason, cancelled_at, cancelled_by_command_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, restaurantId, tableId, sessionId, guestSessionId, type, "Yardım lütfen", status,
                claimed ? UUID.randomUUID() : null, claimed ? now : null,
                resolved ? UUID.randomUUID() : null, resolved ? now : null,
                cancelled ? "TABLE_SESSION_CLOSED" : null, cancelled ? now : null,
                cancelled ? closingCommand(restaurantId, tableId, sessionId) : null, now, now);
        return id;
    }

    /** A call only ends CANCELLED together with a confirmed close of its session (WAITER-04); this is that close. */
    private UUID closingCommand(UUID restaurantId, UUID tableId, UUID sessionId) {
        List<UUID> existing = jdbc.queryForList("SELECT id FROM table_commands WHERE restaurant_id = ? AND table_session_id = ?",
                UUID.class, restaurantId, sessionId);
        if (!existing.isEmpty()) return existing.getFirst();
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO table_commands (id, restaurant_id, command_type, table_id, table_session_id, expected_version,
                                            requested_by_user_id, idempotency_key, request_digest, status, qr_closed_at,
                                            ended_guest_sessions, cancelled_call_count, created_at, updated_at, completed_at)
                VALUES (?, ?, 'CLOSE', ?, ?, 1, ?, ?, 'digest', 'COMPLETED', now(), 1, 1, now(), now(), now())
                """, id, restaurantId, tableId, sessionId, UUID.randomUUID(), "key-" + id);
        return id;
    }

    // ----- request helpers -----

    protected ResultActions raiseCall(FakeBackends.Guest guest, String key, String json) throws Exception {
        return mockMvc.perform(post("/api/v1/waiters/calls")
                .with(guestBrowser(guest.token()))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected ResultActions raiseCall(FakeBackends.Guest guest, String type) throws Exception {
        return raiseCall(guest, "key-" + UUID.randomUUID(), "{\"type\":\"" + type + "\"}");
    }

    protected ResultActions staffCommand(RequestPostProcessor staff, UUID callId, String action, String key,
                                         long expectedVersion) throws Exception {
        return mockMvc.perform(post("/api/v1/waiters/calls/" + callId + "/" + action)
                .with(staff).with(staffCsrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":" + expectedVersion + "}"));
    }

    // ----- browser credentials and CSRF proofs (the cookie contracts of Auth and QR) -----

    /** 32 random bytes, Base64url without padding: the shape of QR's guest token and CSRF proof. */
    protected static String randomProof() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Auth's staff CSRF proof: {@code <expiry epoch seconds>.<43>.<43>}. */
    protected static String staffCsrfProof(long expiresAtEpochSeconds) {
        return expiresAtEpochSeconds + "." + randomProof() + "." + randomProof();
    }

    private static MockHttpServletRequest addCookies(MockHttpServletRequest request, Cookie... added) {
        List<Cookie> all = new java.util.ArrayList<>();
        if (request.getCookies() != null) all.addAll(Arrays.asList(request.getCookies()));
        all.addAll(Arrays.asList(added));
        request.setCookies(all.toArray(Cookie[]::new));
        return request;
    }

    /** Only the guest credential cookie(s), without any CSRF proof: what a forged cross-site request looks like. */
    protected static RequestPostProcessor guestCookie(String... tokens) {
        return request -> addCookies(request,
                Arrays.stream(tokens).map(token -> new Cookie("restofy_guest", token)).toArray(Cookie[]::new));
    }

    /** The guest CSRF proof alone: script-readable cookie, same value as header, and an allowed Origin. */
    protected static RequestPostProcessor guestCsrf() {
        String proof = randomProof();
        return request -> {
            addCookies(request, new Cookie("restofy_guest_csrf", proof));
            request.addHeader("X-Guest-CSRF", proof);
            request.addHeader("Origin", ALLOWED_ORIGIN);
            return request;
        };
    }

    /** A valid browser request of a guest: credential cookie(s) plus the full CSRF/Origin proof. */
    protected static RequestPostProcessor guestBrowser(String... tokens) {
        RequestPostProcessor csrf = guestCsrf();
        RequestPostProcessor cookie = guestCookie(tokens);
        return request -> csrf.postProcessRequest(cookie.postProcessRequest(request));
    }

    /** A valid CSRF/Origin proof of a staff browser request, as Auth issues it (cookie value = header value). */
    protected static RequestPostProcessor staffCsrf() {
        return staffCsrf(Instant.now().plusSeconds(600).getEpochSecond());
    }

    protected static RequestPostProcessor staffCsrf(long expiresAtEpochSeconds) {
        String proof = staffCsrfProof(expiresAtEpochSeconds);
        return request -> {
            addCookies(request, new Cookie("restofy_csrf", proof));
            request.addHeader("X-CSRF-TOKEN", proof);
            request.addHeader("Origin", ALLOWED_ORIGIN);
            return request;
        };
    }

    protected static String newKey() {
        return "key-" + UUID.randomUUID();
    }
}

package com.restofy.waiter.support;

import com.restofy.waiter.domain.enums.StaffRole;
import com.restofy.waiter.infrastructure.security.AuthenticatedStaff;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/**
 * Full-context API test base: the real security filter chain, controllers, services, REST clients and a
 * real PostgreSQL (Testcontainers). Only the QR and Order services are replaced, by a local HTTP stub of
 * the DRAFT contract. The authentication helpers fill the SecurityContext with the verified-staff
 * principal that the future Auth integration will provide; nothing in the production chain is relaxed.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class ApiTestBase {

    protected static final DownstreamStub STUB = DownstreamStub.start();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(STUB::close));
    }

    protected static final UUID RESTAURANT = UUID.fromString("00000000-0000-0000-0000-00000000a001");
    protected static final UUID OTHER_RESTAURANT = UUID.fromString("00000000-0000-0000-0000-00000000b002");

    @DynamicPropertySource
    static void downstreams(DynamicPropertyRegistry registry) {
        registry.add("restofy.services.qr-url", () -> STUB.baseUrl().toString());
        registry.add("restofy.services.order-url", () -> STUB.baseUrl().toString());
        registry.add("restofy.rest-client.read-timeout-ms", () -> "700");
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    protected FakeBackends backends;

    @BeforeEach
    void resetDownstreams() {
        STUB.reset();
        backends = new FakeBackends().installOn(STUB);
        jdbc.update("DELETE FROM service_requests");
    }

    @AfterEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM service_requests");
    }

    protected static RequestPostProcessor asStaff(StaffRole role, UUID restaurantId) {
        return as(new AuthenticatedStaff(UUID.randomUUID(), restaurantId, role), role.name());
    }

    protected static RequestPostProcessor asWaiter() {
        return asStaff(StaffRole.WAITER, RESTAURANT);
    }

    /** The authority is passed separately so tests can build mismatches between authority and principal. */
    protected static RequestPostProcessor as(Object principal, String... authorities) {
        List<SimpleGrantedAuthority> granted = java.util.Arrays.stream(authorities)
                .map(authority -> new SimpleGrantedAuthority("ROLE_" + authority)).toList();
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, granted));
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

    protected void insertServiceRequest(UUID restaurantId, UUID sessionId, String type, String status) {
        jdbc.update("""
                INSERT INTO service_requests (id, restaurant_id, table_session_id, guest_session_id, request_type,
                                              message, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), restaurantId, sessionId, UUID.randomUUID(), type, "Yardım lütfen", status,
                Timestamp.from(Instant.now()));
    }
}

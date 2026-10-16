package com.restofy.waiter.infrastructure.persistence;

import com.restofy.waiter.domain.entity.ServiceRequest;
import com.restofy.waiter.domain.enums.ServiceRequestStatus;
import com.restofy.waiter.domain.enums.ServiceRequestType;
import com.restofy.waiter.infrastructure.persistence.repository.ServiceRequestRepository;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real PostgreSQL (Testcontainers): Flyway applies V101, Hibernate validates the entity against it, and the
 * database itself enforces the call constraints.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// No surrounding transaction: PostgreSQL aborts a transaction after the first constraint violation, which
// would hide the later assertions. Every statement commits on its own and the table is emptied after each test.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ServiceRequestPersistenceTests {

    private static final UUID RESTAURANT = UUID.fromString("00000000-0000-0000-0000-00000000a001");
    private static final UUID OTHER_RESTAURANT = UUID.fromString("00000000-0000-0000-0000-00000000b002");
    private static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER_SESSION = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ServiceRequestRepository repository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @BeforeEach
    @AfterEach
    void emptyTable() {
        jdbc.update("DELETE FROM service_requests");
    }

    private void insert(UUID id, UUID restaurant, UUID session, String type, String status, Instant createdAt) {
        jdbc.update("""
                INSERT INTO service_requests (id, restaurant_id, table_session_id, guest_session_id, request_type, message,
                                              status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, restaurant, session, UUID.randomUUID(), type, "mesaj", status, Timestamp.from(createdAt));
    }

    @Test
    void flywayAppliedOnlyV101AndHibernateValidatesTheSchema() {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);
        assertEquals(List.of("101"), versions, "WAITER-01 must consume exactly one version of the V101-V110 range");
        assertEquals("validate", entityManagerFactory.getProperties().get("hibernate.hbm2ddl.auto"));
    }

    @Test
    void sessionLookupIndexExists() {
        Integer indexes = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE tablename = 'service_requests' AND indexname = 'ix_service_requests_session'",
                Integer.class);
        assertEquals(1, indexes);
    }

    @Test
    void databaseRejectsUnknownTypeUnknownStatusAndNegativeVersion() {
        UUID id = UUID.randomUUID();
        var typeError = assertThrows(DataIntegrityViolationException.class,
                () -> insert(id, RESTAURANT, SESSION, "COMPLAINT", "OPEN", Instant.now()));
        assertTrue(typeError.getMessage().contains("ck_service_requests_type"));
        var statusError = assertThrows(DataIntegrityViolationException.class,
                () -> insert(UUID.randomUUID(), RESTAURANT, SESSION, "WAITER", "ACKNOWLEDGED", Instant.now()));
        assertTrue(statusError.getMessage().contains("ck_service_requests_status"));
        var versionError = assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO service_requests (id, restaurant_id, table_session_id, guest_session_id, request_type, status, version)
                VALUES (?, ?, ?, ?, 'WAITER', 'OPEN', -1)
                """, UUID.randomUUID(), RESTAURANT, SESSION, UUID.randomUUID()));
        assertTrue(versionError.getMessage().contains("ck_service_requests_version"));
    }

    @Test
    void requiredColumnsAreNotNullAndIdIsUnique() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO service_requests (id, table_session_id, guest_session_id, request_type, status) VALUES (?, ?, ?, 'WAITER', 'OPEN')",
                UUID.randomUUID(), SESSION, UUID.randomUUID()));
        UUID id = UUID.randomUUID();
        insert(id, RESTAURANT, SESSION, "WAITER", "OPEN", Instant.now());
        assertThrows(DataIntegrityViolationException.class, () -> insert(id, RESTAURANT, SESSION, "HELP", "OPEN", Instant.now()));
    }

    @Test
    void defaultsAreFilledByTheDatabase() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO service_requests (id, restaurant_id, table_session_id, guest_session_id, request_type, status)
                VALUES (?, ?, ?, ?, 'BILL', 'OPEN')
                """, id, RESTAURANT, SESSION, UUID.randomUUID());
        ServiceRequest loaded = repository.findById(id).orElseThrow();
        assertEquals(0, loaded.getVersion());
        assertNotNull(loaded.getCreatedAt());
        assertNotNull(loaded.getUpdatedAt());
        assertEquals(ServiceRequestType.BILL, loaded.getRequestType());
        assertEquals(ServiceRequestStatus.OPEN, loaded.getStatus());
    }

    @Test
    void unresolvedCallsAreScopedToRestaurantAndSessionAndOrderedOldestFirst() {
        Instant base = Instant.parse("2026-10-16T09:00:00Z");
        UUID second = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        insert(second, RESTAURANT, SESSION, "BILL", "CLAIMED", base.plusSeconds(60));
        insert(first, RESTAURANT, SESSION, "WAITER", "OPEN", base);
        insert(UUID.randomUUID(), RESTAURANT, SESSION, "HELP", "RESOLVED", base);
        insert(UUID.randomUUID(), RESTAURANT, SESSION, "HELP", "CANCELLED", base);
        insert(UUID.randomUUID(), RESTAURANT, OTHER_SESSION, "HELP", "OPEN", base);
        insert(UUID.randomUUID(), OTHER_RESTAURANT, SESSION, "HELP", "OPEN", base);

        List<ServiceRequest> found = repository.findByRestaurantIdAndTableSessionIdInAndStatusInOrderByCreatedAtAscIdAsc(
                RESTAURANT, List.of(SESSION), ServiceRequestStatus.UNRESOLVED);

        assertEquals(List.of(first, second), found.stream().map(ServiceRequest::getId).toList());
        assertTrue(found.stream().allMatch(call -> call.getRestaurantId().equals(RESTAURANT)));
        assertFalse(found.stream().anyMatch(call -> call.getTableSessionId().equals(OTHER_SESSION)));

        List<ServiceRequest> both = repository.findByRestaurantIdAndTableSessionIdInAndStatusInOrderByCreatedAtAscIdAsc(
                RESTAURANT, List.of(SESSION, OTHER_SESSION), ServiceRequestStatus.UNRESOLVED);
        assertEquals(3, both.size());
    }

    @Test
    void entityHasNoWritePathSoWaiter01StaysReadOnly() {
        assertTrue(java.util.Arrays.stream(ServiceRequest.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().startsWith("set")), "Mutations belong to WAITER-02");
        assertTrue(java.util.Arrays.stream(ServiceRequest.class.getConstructors()).findAny().isEmpty(),
                "No public constructor: production code cannot create calls yet");
    }
}

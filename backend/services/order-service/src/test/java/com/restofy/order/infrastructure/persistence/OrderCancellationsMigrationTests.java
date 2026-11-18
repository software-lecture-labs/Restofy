package com.restofy.order.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-13: the order cancellation migration (V47) on a clean, throw-away PostgreSQL schema. The whole chain V41-V50 runs from
 * nothing, uses no number outside the Order range, passes Flyway's own validation (checksums, ordering) and leaves the
 * audit model in place: SERVICE is a real kind of author next to GUEST and STAFF, the staff member a service claims to act
 * for has a column of its own, and the guards keep both unchangeable. V50 (the version of a decision and the
 * CUSTOMER_REQUEST reason) is a migration of its own, applied on top of V47 as it was shared on dev: V47 is never edited.
 */
@SpringBootTest
class OrderCancellationsMigrationTests {

    @Autowired
    private DataSource dataSource;

    private Flyway flyway(String schema) {
        return Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load();
    }

    private void drop(String schema) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private String one(Statement statement, String sql) throws Exception {
        try (ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    @Test
    void theWholeChainRunsOnACleanDatabaseWithinTheOrderRangeAndValidates() throws Exception {
        String schema = "cancel_chain_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway flyway = flyway(schema);
            flyway.migrate();

            List<String> versions = new ArrayList<>();
            for (MigrationInfo info : flyway.info().applied()) {
                if (info.getVersion() == null) {
                    continue;                                   // Flyway's own schema-creation entry
                }
                assertTrue(info.getState().isApplied() && info.getState().isResolved(), info.getVersion().toString());
                versions.add(info.getVersion().toString());
            }
            assertEquals(List.of("41", "42", "43", "44", "45", "46", "47", "48", "49", "50"), versions,
                    "the chain V41-V50: exactly the Order range, no number outside it was taken");
            assertEquals(0, flyway.info().pending().length);
            flyway.validate();                                  // checksums of every applied file still match
            assertEquals(0, flyway.migrate().migrationsExecuted, "a second run has nothing to do");
        } finally {
            drop(schema);                                       // only the throw-away schema created by this test
        }
    }

    @Test
    void serviceIsARealKindOfAuthorAndTheClaimedStaffMemberHasItsOwnColumn() throws Exception {
        String schema = "cancel_audit_" + UUID.randomUUID().toString().replace("-", "");
        try {
            flyway(schema).migrate();
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                // every query below names the schema: the pooled connection's search_path is never changed

                assertEquals("uuid", one(statement, "SELECT data_type FROM information_schema.columns WHERE table_schema = '"
                        + schema + "' AND table_name = 'order_cancellations' AND column_name = 'reported_staff_id'"));
                assertEquals("YES", one(statement, "SELECT is_nullable FROM information_schema.columns WHERE table_schema = '"
                        + schema + "' AND table_name = 'order_cancellations' AND column_name = 'reported_staff_id'"));

                String requester = one(statement, "SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c JOIN pg_namespace n"
                        + " ON n.oid = c.connamespace WHERE n.nspname = '" + schema
                        + "' AND c.conname = 'ck_order_cancellations_requester'");
                assertTrue(requester.contains("'GUEST'") && requester.contains("'STAFF'") && requester.contains("'SERVICE'"),
                        requester);
                assertFalse(requester.contains("'SYSTEM'"), "no uncontrolled widening of the kinds: " + requester);
                String source = one(statement, "SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c JOIN pg_namespace n"
                        + " ON n.oid = c.connamespace WHERE n.nspname = '" + schema
                        + "' AND c.conname = 'ck_order_cancellation_reasons_source'");
                assertTrue(source.contains("'GUEST'") && source.contains("'STAFF'") && source.contains("'SERVICE'"), source);
                // the old rule that a reason's author kind equals its source stays
                assertTrue(one(statement, "SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c JOIN pg_namespace n"
                        + " ON n.oid = c.connamespace WHERE n.nspname = '" + schema
                        + "' AND c.conname = 'ck_order_cancellation_reasons_actor'").contains("added_by_type"));
                // only the Kitchen service, and a claim only next to a service request
                assertTrue(one(statement, "SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c JOIN pg_namespace n"
                        + " ON n.oid = c.connamespace WHERE n.nspname = '" + schema
                        + "' AND c.conname = 'ck_order_cancellations_service_requester'").contains("KITCHEN"));
                assertTrue(one(statement, "SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c JOIN pg_namespace n"
                        + " ON n.oid = c.connamespace WHERE n.nspname = '" + schema
                        + "' AND c.conname = 'ck_order_cancellations_reported_staff'").contains("reported_staff_id IS NULL"));

                // the guards: the claim is unchangeable like the author, and the append-only rules are all still there
                String guard = one(statement, "SELECT p.prosrc FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace"
                        + " WHERE n.nspname = '" + schema + "' AND p.proname = 'order_cancellations_guard'");
                assertTrue(guard.contains("reported_staff_id") && guard.contains("requested_by_type")
                        && guard.contains("requested_by_id") && guard.contains("requested_by_role")
                        && guard.contains("never deleted") && guard.contains("decision is final")
                        && guard.contains("cannot be taken back"), guard);
                assertEquals("3", one(statement, "SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid"
                        + " JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = '" + schema
                        + "' AND t.tgname IN ('trg_order_cancellations_guard', 'trg_order_cancellation_items_guard',"
                        + " 'trg_order_cancellation_reasons_guard') AND NOT t.tgisinternal"));
            }
        } finally {
            drop(schema);
        }
    }

    @Test
    void theTemporaryStaffKitchenServiceRepresentationIsGoneFromTheSchemaAndTheResources() throws Exception {
        String schema = "cancel_clean_" + UUID.randomUUID().toString().replace("-", "");
        try {
            flyway(schema).migrate();
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                // no constraint or function of the schema speaks of the old "<SERVICE>_SERVICE" role or "service:" ids
                assertEquals("0", one(statement, "SELECT count(*) FROM pg_constraint c JOIN pg_namespace n ON n.oid ="
                        + " c.connamespace WHERE n.nspname = '" + schema + "' AND (pg_get_constraintdef(c.oid) LIKE"
                        + " '%KITCHEN\\_SERVICE%' OR pg_get_constraintdef(c.oid) LIKE '%service:%')"));
            }
        } finally {
            drop(schema);
        }
        String v47 = new String(getClass().getResourceAsStream("/db/migration/V47__create_order_cancellations.sql")
                .readAllBytes(), StandardCharsets.UTF_8);
        assertFalse(v47.contains("KITCHEN_SERVICE") || v47.contains("service:KITCHEN"), "the old mapping is not in V47");
        // every migration file of the service is in the Order range: no number was borrowed from anybody
        File directory = new File(getClass().getResource("/db/migration/V47__create_order_cancellations.sql")
                .toURI()).getParentFile();
        List<String> numbers = new ArrayList<>();
        for (File file : directory.listFiles((dir, name) -> name.endsWith(".sql"))) {
            numbers.add(file.getName().substring(1, file.getName().indexOf("__")));
        }
        Collections.sort(numbers);
        assertEquals(List.of("41", "42", "43", "44", "45", "46", "47", "48", "49", "50"), numbers);
    }

    @Test
    void v47StaysAsSharedOnDevAndV50AppliesOnTopOfAnExistingDatabase() throws Exception {
        // V47 is already applied in every shared Order database: it must not carry the Kitchen agreement, V50 does
        String v47 = new String(getClass().getResourceAsStream("/db/migration/V47__create_order_cancellations.sql")
                .readAllBytes(), StandardCharsets.UTF_8);
        assertFalse(v47.contains("decided_order_version") || v47.contains("CUSTOMER_REQUEST"),
                "the shared V47 is not edited again: the agreement lives in V50");

        String schema = "cancel_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try {
            // a database that stopped at V49, like every Order database that applied dev before V50 existed
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").target("49").load().migrate();
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                assertEquals("0", one(statement, "SELECT count(*) FROM information_schema.columns WHERE table_schema = '"
                        + schema + "' AND table_name = 'order_cancellations' AND column_name = 'decided_order_version'"));
            }

            Flyway flyway = flyway(schema);
            assertEquals(1, flyway.migrate().migrationsExecuted, "only V50 is left to apply");
            flyway.validate();                                  // the checksums of V41-V49 are the ones already applied
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                assertEquals("bigint", one(statement, "SELECT data_type FROM information_schema.columns WHERE table_schema = '"
                        + schema + "' AND table_name = 'order_cancellations' AND column_name = 'decided_order_version'"));
                assertTrue(one(statement, "SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c JOIN pg_namespace n"
                        + " ON n.oid = c.connamespace WHERE n.nspname = '" + schema
                        + "' AND c.conname = 'ck_order_cancellation_reasons_code'").contains("'CUSTOMER_REQUEST'"));
                assertTrue(one(statement, "SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c JOIN pg_namespace n"
                        + " ON n.oid = c.connamespace WHERE n.nspname = '" + schema
                        + "' AND c.conname = 'ck_order_cancellations_decided_order_version'").contains("decided_order_version"));
                String guard = one(statement, "SELECT p.prosrc FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace"
                        + " WHERE n.nspname = '" + schema + "' AND p.proname = 'order_cancellations_guard'");
                assertTrue(guard.contains("the order version of its decision is final") && guard.contains("never deleted")
                        && guard.contains("cannot be taken back"), guard);
            }
        } finally {
            drop(schema);
        }
    }
}

package com.restofy.order.api.controller;

import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V48: what the DATABASE itself guarantees about table-session closures, whatever the application code does. */
class TableSessionClosureDatabaseTests extends AbstractCartApiTest {

    private static final String FINGERPRINT = "a".repeat(64);

    private static void refuses(Runnable statement, String message) {
        RuntimeException error = assertThrows(RuntimeException.class, statement::run);
        StringBuilder all = new StringBuilder();
        for (Throwable t = error; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append(" | ");
        }
        assertTrue(all.toString().contains(message), all.toString());
    }

    private void insert(UUID operation, UUID session, String status, String completedAt, String releasedAt) {
        jdbc.update("INSERT INTO table_session_closures (operation_id, restaurant_id, table_session_id, status,"
                + " order_count, scope_fingerprint, prepared_at, completed_at, released_at)"
                + " VALUES (?, ?, ?, ?, 1, ?, now(), " + completedAt + ", " + releasedAt + ")", operation,
                DevFixtures.RESTAURANT_A, session, status, FINGERPRINT);
    }

    private void prepared(UUID operation, UUID session) {
        insert(operation, session, "PREPARED", "NULL", "NULL");
    }

    private String statusOf(UUID operation) {
        return jdbc.queryForObject("SELECT status FROM table_session_closures WHERE operation_id = ?", String.class,
                operation);
    }

    @Test
    void aTableSessionHasAtMostOneLiveClosure() {
        UUID session = DevFixtures.TABLE_SESSION_A1;
        prepared(UUID.randomUUID(), session);

        refuses(() -> prepared(UUID.randomUUID(), session), "uq_table_session_closures_live");
        refuses(() -> insert(UUID.randomUUID(), session, "COMPLETED", "now()", "NULL"), "uq_table_session_closures_live");
        // another session is independent
        prepared(UUID.randomUUID(), DevFixtures.TABLE_SESSION_B1);
        assertEquals(2, count("table_session_closures"));
    }

    @Test
    void aReleasedClosureDoesNotBlockANewOne() {
        UUID session = DevFixtures.TABLE_SESSION_A1;
        UUID first = UUID.randomUUID();
        prepared(first, session);
        jdbc.update("UPDATE table_session_closures SET status = 'RELEASED', released_at = now() WHERE operation_id = ?",
                first);

        prepared(UUID.randomUUID(), session);

        assertEquals(2, count("table_session_closures"));
    }

    @Test
    void anOperationIdExistsOnce() {
        UUID operation = UUID.randomUUID();
        prepared(operation, DevFixtures.TABLE_SESSION_A1);

        refuses(() -> prepared(operation, DevFixtures.TABLE_SESSION_B1), "pk_table_session_closures");
    }

    @Test
    void theStatusAndItsTimestampsMustAgree() {
        UUID session = DevFixtures.TABLE_SESSION_A1;

        refuses(() -> insert(UUID.randomUUID(), session, "PREPARED", "now()", "NULL"), "ck_table_session_closures_times");
        refuses(() -> insert(UUID.randomUUID(), session, "COMPLETED", "NULL", "NULL"), "ck_table_session_closures_times");
        refuses(() -> insert(UUID.randomUUID(), session, "RELEASED", "NULL", "NULL"), "ck_table_session_closures_times");
        refuses(() -> insert(UUID.randomUUID(), session, "RELEASED", "now()", "now()"), "ck_table_session_closures_times");
        refuses(() -> insert(UUID.randomUUID(), session, "LOST", "NULL", "NULL"), "ck_table_session_closures_status");
        assertEquals(0, count("table_session_closures"));
    }

    @Test
    void theFingerprintAndTheOrderCountMustBeValid() {
        UUID session = DevFixtures.TABLE_SESSION_A1;

        refuses(() -> jdbc.update("INSERT INTO table_session_closures (operation_id, restaurant_id, table_session_id,"
                + " status, order_count, scope_fingerprint, prepared_at) VALUES (?, ?, ?, 'PREPARED', 1, 'not-hex', now())",
                UUID.randomUUID(), DevFixtures.RESTAURANT_A, session), "ck_table_session_closures_fingerprint");
        refuses(() -> jdbc.update("INSERT INTO table_session_closures (operation_id, restaurant_id, table_session_id,"
                + " status, order_count, scope_fingerprint, prepared_at) VALUES (?, ?, ?, 'PREPARED', 0, ?, now())",
                UUID.randomUUID(), DevFixtures.RESTAURANT_A, session, FINGERPRINT), "ck_table_session_closures_count");
    }

    @Test
    void aClosureIsNeverDeleted() {
        UUID operation = UUID.randomUUID();
        prepared(operation, DevFixtures.TABLE_SESSION_A1);

        refuses(() -> jdbc.update("DELETE FROM table_session_closures WHERE operation_id = ?", operation),
                "never deleted");
        assertEquals(1, count("table_session_closures"));
    }

    @Test
    void theFactsOfAClosureNeverChange() {
        UUID operation = UUID.randomUUID();
        prepared(operation, DevFixtures.TABLE_SESSION_A1);

        refuses(() -> jdbc.update("UPDATE table_session_closures SET scope_fingerprint = ? WHERE operation_id = ?",
                "b".repeat(64), operation), "is immutable");
        refuses(() -> jdbc.update("UPDATE table_session_closures SET table_session_id = ? WHERE operation_id = ?",
                UUID.randomUUID(), operation), "is immutable");
        refuses(() -> jdbc.update("UPDATE table_session_closures SET order_count = 5 WHERE operation_id = ?", operation),
                "is immutable");
        refuses(() -> jdbc.update("UPDATE table_session_closures SET prepared_at = now() - interval '1 day'"
                + " WHERE operation_id = ?", operation), "is immutable");
    }

    @Test
    void aPreparedClosureMovesOnceAndThenItIsFinal() {
        UUID completed = UUID.randomUUID();
        prepared(completed, DevFixtures.TABLE_SESSION_A1);
        jdbc.update("UPDATE table_session_closures SET status = 'COMPLETED', completed_at = now() WHERE operation_id = ?",
                completed);
        assertEquals("COMPLETED", statusOf(completed));

        refuses(() -> jdbc.update("UPDATE table_session_closures SET status = 'RELEASED', completed_at = NULL,"
                + " released_at = now() WHERE operation_id = ?", completed), "outcome is final");
        assertEquals("COMPLETED", statusOf(completed));

        UUID released = UUID.randomUUID();
        prepared(released, DevFixtures.TABLE_SESSION_B1);
        jdbc.update("UPDATE table_session_closures SET status = 'RELEASED', released_at = now() WHERE operation_id = ?",
                released);
        refuses(() -> jdbc.update("UPDATE table_session_closures SET status = 'COMPLETED', released_at = NULL,"
                + " completed_at = now() WHERE operation_id = ?", released), "outcome is final");
        assertEquals("RELEASED", statusOf(released));
    }
}

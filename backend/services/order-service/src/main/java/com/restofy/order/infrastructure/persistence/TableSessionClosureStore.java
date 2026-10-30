package com.restofy.order.infrastructure.persistence;

import com.restofy.order.domain.closure.OrderClosureFacts;
import com.restofy.order.domain.enums.ClosureStatus;
import com.restofy.order.domain.enums.FulfillmentStatus;
import com.restofy.order.domain.enums.PaymentStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence of table-session closures (V48) and the lock that orders them against checkouts (ADR-QR-001 K8/K9).
 *
 * <p>The ordering lock is a PostgreSQL transaction-level advisory lock on (restaurant, table session). A checkout takes
 * it SHARED, so checkouts of one session never wait for each other; a closure preparation takes it EXCLUSIVE, so it
 * waits for every checkout already in flight and keeps new ones out until it commits. Because it lives in the database,
 * it holds across several Order Service instances. Both writers must call it as the first statement of their
 * transaction. It cannot deadlock with the cart or order-number locks: a preparation takes no other lock.
 */
@Repository
public class TableSessionClosureStore {

    public record Closure(UUID operationId, UUID restaurantId, UUID tableSessionId, ClosureStatus status,
                          int orderCount, String scopeFingerprint, Instant preparedAt, Instant completedAt,
                          Instant releasedAt) {
    }

    private static final String COLUMNS = "operation_id, restaurant_id, table_session_id, status, order_count,"
            + " scope_fingerprint, prepared_at, completed_at, released_at";

    private final JdbcTemplate jdbc;

    public TableSessionClosureStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A checkout: waits only for a closure preparation in progress. Held until the transaction ends. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForCheckout(UUID restaurantId, UUID tableSessionId) {
        jdbc.query("SELECT pg_advisory_xact_lock_shared(hashtextextended(?, 0))", rs -> {
        }, lockKey(restaurantId, tableSessionId));
    }

    /** A closure preparation or confirmation: waits for every checkout in flight. Held until the transaction ends. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForClosure(UUID restaurantId, UUID tableSessionId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {
        }, lockKey(restaurantId, tableSessionId));
    }

    private static String lockKey(UUID restaurantId, UUID tableSessionId) {
        return "table-session-closure:" + restaurantId + ":" + tableSessionId;
    }

    public Optional<Closure> findByOperation(UUID operationId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM table_session_closures WHERE operation_id = ?",
                (rs, row) -> map(rs), operationId).stream().findFirst();
    }

    /** The PREPARED or COMPLETED closure of the session, if any: the one that stops new orders. */
    public Optional<Closure> findLive(UUID restaurantId, UUID tableSessionId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM table_session_closures WHERE restaurant_id = ?"
                        + " AND table_session_id = ? AND status IN ('PREPARED', 'COMPLETED')",
                (rs, row) -> map(rs), restaurantId, tableSessionId).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insertPrepared(UUID operationId, UUID restaurantId, UUID tableSessionId, int orderCount,
                               String scopeFingerprint, Instant now) {
        jdbc.update("INSERT INTO table_session_closures (" + COLUMNS + ") VALUES (?, ?, ?, 'PREPARED', ?, ?, ?, NULL, NULL)",
                operationId, restaurantId, tableSessionId, orderCount, scopeFingerprint,
                JdbcTimestamps.timestamp(now));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markCompleted(UUID operationId, Instant now) {
        jdbc.update("UPDATE table_session_closures SET status = 'COMPLETED', completed_at = ? WHERE operation_id = ?",
                JdbcTimestamps.timestamp(now), operationId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markReleased(UUID operationId, Instant now) {
        jdbc.update("UPDATE table_session_closures SET status = 'RELEASED', released_at = ? WHERE operation_id = ?",
                JdbcTimestamps.timestamp(now), operationId);
    }

    /**
     * Everything the closure decision needs about every order of the session, whichever guest placed it, tenant scoped.
     * One statement, so the facts of one order are read together.
     */
    public List<OrderClosureFacts> loadFacts(UUID restaurantId, UUID tableSessionId) {
        return jdbc.query("""
                SELECT o.id, o.fulfillment_status, o.payment_status, o.current_due_minor,
                       EXISTS (SELECT 1 FROM order_cancellations c
                               WHERE c.order_id = o.id AND c.status = 'REQUESTED') AS open_cancellation,
                       coalesce((SELECT sum(greatest(c.refund_required_minor - c.refunded_minor, 0))
                                 FROM order_cancellations c
                                 WHERE c.order_id = o.id AND c.status = 'APPLIED'), 0) AS outstanding_refund,
                       EXISTS (SELECT 1 FROM payment_captures p
                               WHERE p.order_id = o.id AND p.status = 'PENDING_VERIFICATION') AS pending_capture,
                       EXISTS (SELECT 1 FROM payment_reconciliation_cases r
                               WHERE r.order_id = o.id) AS reconciliation_case
                FROM orders o
                WHERE o.restaurant_id = ? AND o.table_session_id = ?
                ORDER BY o.id
                """, (rs, row) -> new OrderClosureFacts(rs.getObject("id", UUID.class),
                FulfillmentStatus.valueOf(rs.getString("fulfillment_status")),
                PaymentStatus.valueOf(rs.getString("payment_status")), rs.getLong("current_due_minor"),
                rs.getBoolean("open_cancellation"), rs.getLong("outstanding_refund"),
                rs.getBoolean("pending_capture"), rs.getBoolean("reconciliation_case")),
                restaurantId, tableSessionId);
    }

    private static Closure map(ResultSet rs) throws SQLException {
        return new Closure(rs.getObject("operation_id", UUID.class), rs.getObject("restaurant_id", UUID.class),
                rs.getObject("table_session_id", UUID.class), ClosureStatus.valueOf(rs.getString("status")),
                rs.getInt("order_count"), rs.getString("scope_fingerprint"),
                rs.getTimestamp("prepared_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
                rs.getTimestamp("released_at") == null ? null : rs.getTimestamp("released_at").toInstant());
    }
}

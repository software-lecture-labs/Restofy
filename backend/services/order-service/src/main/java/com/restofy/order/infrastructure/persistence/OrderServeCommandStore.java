package com.restofy.order.infrastructure.persistence;

import com.restofy.order.domain.exception.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class OrderServeCommandStore {

    public record Decision(UUID commandId, UUID orderId, UUID tableSessionId, String requestDigest, long orderVersion,
                           Instant decidedAt, Instant servedAt, ErrorCode rejection) {
    }

    private final JdbcTemplate jdbc;

    public OrderServeCommandStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Decision> find(UUID restaurantId, UUID commandId) {
        return jdbc.query("""
                        SELECT order_id, table_session_id, request_digest, order_version, decided_at, served_at, rejection_code
                        FROM order_serve_commands
                        WHERE restaurant_id = ? AND command_id = ?
                        """,
                (rs, _) -> {
                    String rejection = rs.getString("rejection_code");
                    Timestamp servedAt = rs.getTimestamp("served_at");
                    return new Decision(commandId, rs.getObject("order_id", UUID.class),
                            rs.getObject("table_session_id", UUID.class),
                            rs.getString("request_digest"), rs.getLong("order_version"),
                            rs.getTimestamp("decided_at").toInstant(), servedAt == null ? null : servedAt.toInstant(),
                            rejection == null ? null : ErrorCode.valueOf(rejection));
                }, restaurantId, commandId).stream().findFirst();
    }

    public boolean insert(UUID restaurantId, UUID staffId, Instant requestedAt, Decision result) {
        return jdbc.update("""
                        INSERT INTO order_serve_commands (restaurant_id, command_id, order_id, table_session_id, request_digest,
                            requested_by, requested_at, decided_at, order_version, served_at, rejection_code)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (restaurant_id, command_id) DO NOTHING
                        """, restaurantId, result.commandId(), result.orderId(), result.tableSessionId(),
                result.requestDigest(), staffId,
                Timestamp.from(requestedAt), Timestamp.from(result.decidedAt()), result.orderVersion(),
                result.servedAt() == null ? null : Timestamp.from(result.servedAt()),
                result.rejection() == null ? null : result.rejection().name()) == 1;
    }
}

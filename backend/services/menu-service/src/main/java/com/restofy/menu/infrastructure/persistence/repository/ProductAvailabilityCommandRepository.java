package com.restofy.menu.infrastructure.persistence.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The idempotency records of availability commands ({@code product_availability_commands}). Plain JDBC on purpose:
 * claiming a command id needs {@code INSERT .. ON CONFLICT DO NOTHING}, which waits for a concurrent claim of the same
 * id to finish and then answers "already taken" without poisoning the transaction (an ordinary insert that violates
 * the key would abort it). It joins the surrounding transaction, so a rejected command leaves no row. Every statement
 * carries the restaurant id.
 *
 * <p>The conflict clause names no target on purpose: the table has two unique keys that both contain the command id
 * (the primary key and the one the history foreign key needs). With a named target only that key's violation is
 * swallowed, and a concurrent duplicate that is caught by the other key surfaces as an error instead of "already taken".
 */
@Repository
public class ProductAvailabilityCommandRepository {

    private static final String CLAIM = """
            INSERT INTO product_availability_commands (restaurant_id, command_id, product_id, source, target_status,
                reason_code, reason_text, expected_version, outcome, resulting_version, requested_by_user_id, created_at)
            VALUES (:restaurantId, :commandId, :productId, :source, :targetStatus, :reasonCode, :reasonText,
                :expectedVersion, 'APPLIED', 0, :requestedBy, :createdAt)
            ON CONFLICT DO NOTHING
            """;

    private static final String FIND = """
            SELECT product_id, source, target_status, reason_code, reason_text, expected_version, outcome,
                   resulting_version, requested_by_user_id
              FROM product_availability_commands
             WHERE restaurant_id = :restaurantId AND command_id = :commandId
            """;

    private static final String COMPLETE = """
            UPDATE product_availability_commands SET outcome = :outcome, resulting_version = :resultingVersion
             WHERE restaurant_id = :restaurantId AND command_id = :commandId
            """;

    private static final String RESTAURANT_ID = "restaurantId";
    private static final String COMMAND_ID = "commandId";

    private final NamedParameterJdbcTemplate jdbc;

    public ProductAvailabilityCommandRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** What a command asked for, as stored. Compared field by field with a repeated command. */
    public record Content(UUID productId, String source, String targetStatus, String reasonCode, String reasonText,
            Long expectedVersion, UUID requestedBy) { }

    /** A stored command: its content and what it produced. */
    public record Stored(Content content, String outcome, long resultingVersion) { }

    /** @return {@code true} when this call took the command id, {@code false} when it was already taken */
    public boolean claim(UUID restaurantId, UUID commandId, Content content, Instant createdAt) {
        MapSqlParameterSource parameters = key(restaurantId, commandId)
                .addValue("productId", content.productId())
                .addValue("source", content.source())
                .addValue("targetStatus", content.targetStatus())
                .addValue("reasonCode", content.reasonCode(), Types.VARCHAR)
                .addValue("reasonText", content.reasonText(), Types.VARCHAR)
                .addValue("expectedVersion", content.expectedVersion(), Types.BIGINT)
                .addValue("requestedBy", content.requestedBy())
                .addValue("createdAt", java.sql.Timestamp.from(createdAt));
        return jdbc.update(CLAIM, parameters) == 1;
    }

    public Optional<Stored> find(UUID restaurantId, UUID commandId) {
        return jdbc.query(FIND, key(restaurantId, commandId),
                (rows, index) -> new Stored(new Content(rows.getObject("product_id", UUID.class), rows.getString("source"),
                        rows.getString("target_status"), rows.getString("reason_code"), rows.getString("reason_text"),
                        (Long) rows.getObject("expected_version"), rows.getObject("requested_by_user_id", UUID.class)),
                        rows.getString("outcome"), rows.getLong("resulting_version"))).stream().findFirst();
    }

    /** Records what the claimed command produced. */
    public void complete(UUID restaurantId, UUID commandId, String outcome, long resultingVersion) {
        jdbc.update(COMPLETE, key(restaurantId, commandId)
                .addValue("outcome", outcome).addValue("resultingVersion", resultingVersion));
    }

    /** The two values that identify a command; every statement starts from them. */
    private static MapSqlParameterSource key(UUID restaurantId, UUID commandId) {
        return new MapSqlParameterSource(RESTAURANT_ID, restaurantId).addValue(COMMAND_ID, commandId);
    }
}

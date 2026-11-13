package com.restofy.menu.infrastructure.persistence.repository;

import com.restofy.menu.domain.entity.ProductAvailabilityCommand;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * The idempotency records of availability commands. Claiming a command id uses {@code INSERT .. ON CONFLICT DO NOTHING}: it
 * waits for a concurrent claim of the same id to finish and then answers "already taken" without poisoning the transaction
 * (an ordinary insert that violates the key would abort it). The conflict clause names no target on purpose: the table has two
 * unique keys that both contain the command id (the primary key and the one the history foreign key needs), and with a named
 * target only that key's violation is swallowed. Every query carries the restaurant id; the inherited findById is not used.
 */
public interface ProductAvailabilityCommandRepository
        extends JpaRepository<ProductAvailabilityCommand, ProductAvailabilityCommand.Key> {

    /** @return 1 when this call took the command id, 0 when it was already taken */
    @Modifying
    @Query(value = """
            INSERT INTO product_availability_commands (restaurant_id, command_id, product_id, source, target_status,
                reason_code, reason_text, expected_version, outcome, resulting_version, requested_by_user_id, created_at)
            VALUES (:#{#command.restaurantId}, :#{#command.commandId}, :#{#command.productId}, :#{#command.source.name()},
                :#{#command.targetStatus.name()}, CAST(:#{#command.reasonCode} AS varchar), CAST(:#{#command.reasonText} AS text),
                CAST(:#{#command.expectedVersion} AS bigint), :#{#command.outcome.name()}, :#{#command.resultingVersion},
                :#{#command.requestedByUserId}, :#{#command.createdAt})
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int claim(@Param("command") ProductAvailabilityCommand command);

    Optional<ProductAvailabilityCommand> findByRestaurantIdAndCommandId(UUID restaurantId, UUID commandId);
}

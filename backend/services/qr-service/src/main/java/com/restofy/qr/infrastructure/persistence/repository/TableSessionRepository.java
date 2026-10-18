package com.restofy.qr.infrastructure.persistence.repository;

import com.restofy.qr.domain.entity.TableSession;
import com.restofy.qr.domain.enums.TableSessionStatus;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TableSessionRepository extends JpaRepository<TableSession, UUID> {
    @Query("select s.tableId from TableSession s where s.restaurantId = :restaurantId and s.id = :id")
    Optional<UUID> locateTableId(UUID restaurantId, UUID id);

    Optional<TableSession> findByRestaurantIdAndId(UUID restaurantId, UUID id);

    Optional<TableSession> findByRestaurantIdAndTableIdAndStatusIn(UUID restaurantId, UUID tableId,
                                                                  Collection<TableSessionStatus> statuses);
}

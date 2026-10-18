package com.restofy.qr.infrastructure.persistence.repository;

import com.restofy.qr.domain.entity.TableSession;
import com.restofy.qr.domain.enums.TableSessionStatus;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TableSessionRepository extends JpaRepository<TableSession, UUID> {
    Optional<TableSession> findByRestaurantIdAndTableIdAndStatusIn(UUID restaurantId, UUID tableId,
                                                                  Collection<TableSessionStatus> statuses);
}

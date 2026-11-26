package com.restofy.qr.infrastructure.persistence.repository;

import com.restofy.qr.domain.entity.TableCleanCommand;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TableCleanCommandRepository extends JpaRepository<TableCleanCommand, UUID> {
    Optional<TableCleanCommand> findByRestaurantIdAndCommandId(UUID restaurantId, UUID commandId);
}

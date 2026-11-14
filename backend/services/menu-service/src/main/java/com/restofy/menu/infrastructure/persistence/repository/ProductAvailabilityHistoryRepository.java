package com.restofy.menu.infrastructure.persistence.repository;

import com.restofy.menu.domain.entity.ProductAvailabilityHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Append-only: services only save new entries and read them back newest first within one tenant. */
public interface ProductAvailabilityHistoryRepository extends JpaRepository<ProductAvailabilityHistory, UUID> {

    List<ProductAvailabilityHistory> findByRestaurantIdAndProductIdOrderByChangedAtDescIdAsc(UUID restaurantId,
            UUID productId);
}

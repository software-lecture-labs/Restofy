package com.restofy.menu.infrastructure.persistence.repository;

import com.restofy.menu.domain.entity.ProductAvailability;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every query carries the restaurant id; the inherited findById/findAll are not tenant-scoped and must
 * not be used by services. A missing row is a normal answer: the product is then not orderable.
 */
public interface ProductAvailabilityRepository extends JpaRepository<ProductAvailability, UUID> {

    Optional<ProductAvailability> findByRestaurantIdAndProductId(UUID restaurantId, UUID productId);

    /** Batch lookup for a whole product page (no per-row queries). */
    List<ProductAvailability> findByRestaurantIdAndProductIdIn(UUID restaurantId, Collection<UUID> productIds);
}

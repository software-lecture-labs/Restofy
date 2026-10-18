package com.restofy.menu.infrastructure.persistence.repository;

import com.restofy.menu.domain.entity.ProductOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

/**
 * Every query carries the restaurant id and the product id. The inherited findById/findAll are not tenant-scoped
 * and must not be used by services.
 */
public interface ProductOptionRepository extends JpaRepository<ProductOption, UUID>, JpaSpecificationExecutor<ProductOption> {

    /** Tenant- and product-scoped lookup: an option of another product or restaurant is simply absent. */
    Optional<ProductOption> findByIdAndProductIdAndRestaurantId(UUID id, UUID productId, UUID restaurantId);

    long countByRestaurantIdAndProductIdAndActiveTrue(UUID restaurantId, UUID productId);
}

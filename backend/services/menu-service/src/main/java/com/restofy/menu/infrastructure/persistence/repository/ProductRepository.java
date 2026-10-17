package com.restofy.menu.infrastructure.persistence.repository;

import com.restofy.menu.domain.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

/**
 * The queries declared here all carry the restaurant id. The inherited findById/findAll are not
 * tenant-scoped: services must use the declared methods (or a specification that starts with the
 * restaurant predicate) instead.
 */
public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {

    /** Tenant-scoped lookup. Never load a product by id alone. */
    Optional<Product> findByIdAndRestaurantId(UUID id, UUID restaurantId);
}

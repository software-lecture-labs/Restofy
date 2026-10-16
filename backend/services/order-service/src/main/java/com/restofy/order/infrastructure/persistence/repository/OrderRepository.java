package com.restofy.order.infrastructure.persistence.repository;

import com.restofy.order.domain.entity.Order;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    /** Tenant-scoped lookup: an id from another restaurant is simply not found. */
    @EntityGraph(attributePaths = {"items", "items.options"})
    Optional<Order> findByIdAndRestaurantId(UUID id, UUID restaurantId);
}

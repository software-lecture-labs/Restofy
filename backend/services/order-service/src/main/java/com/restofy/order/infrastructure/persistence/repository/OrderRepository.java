package com.restofy.order.infrastructure.persistence.repository;

import com.restofy.order.domain.entity.Order;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID>, JpaSpecificationExecutor<Order> {

    /** Ordered units per order for a page of orders (one query instead of loading every line). */
    interface UnitCount {
        UUID getOrderId();

        Long getUnits();
    }

    @Query("select i.order.id as orderId, sum(i.orderedQuantity) as units from OrderItem i"
            + " where i.order.id in :orderIds group by i.order.id")
    List<UnitCount> unitCounts(@Param("orderIds") Collection<UUID> orderIds);


    /** Tenant-scoped lookup: an id from another restaurant is simply not found. */
    @EntityGraph(attributePaths = {"items", "items.options"})
    Optional<Order> findByIdAndRestaurantId(UUID id, UUID restaurantId);
}

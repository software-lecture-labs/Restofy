package com.restofy.kitchen.infrastructure.persistence.repository;

import com.restofy.kitchen.domain.entity.KitchenOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface KitchenOrderRepository extends JpaRepository<KitchenOrder, UUID>, JpaSpecificationExecutor<KitchenOrder> {

	/** Lookups are tenant scoped; there is deliberately no lookup by order id alone. */
	Optional<KitchenOrder> findByRestaurantIdAndOrderId(UUID restaurantId, UUID orderId);
}

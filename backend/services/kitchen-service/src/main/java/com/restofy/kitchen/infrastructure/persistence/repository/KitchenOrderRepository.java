package com.restofy.kitchen.infrastructure.persistence.repository;

import com.restofy.kitchen.domain.entity.KitchenOrder;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface KitchenOrderRepository extends JpaRepository<KitchenOrder, UUID>, JpaSpecificationExecutor<KitchenOrder> {

	/** Lookups are tenant scoped; there is deliberately no lookup by order id alone. */
	Optional<KitchenOrder> findByRestaurantIdAndOrderId(UUID restaurantId, UUID orderId);

	/**
	 * Tenant scoped lookup that takes a row lock (SELECT ... FOR UPDATE) so concurrent preparation commands on the
	 * same order are serialized. The lock wait is bounded; a timeout surfaces as a locking failure (409).
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
	@Query("select o from KitchenOrder o where o.restaurantId = :restaurantId and o.orderId = :orderId")
	Optional<KitchenOrder> findForUpdate(@Param("restaurantId") UUID restaurantId, @Param("orderId") UUID orderId);
}

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

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KitchenOrderRepository extends JpaRepository<KitchenOrder, UUID>, JpaSpecificationExecutor<KitchenOrder> {

	/** Lookups are tenant scoped; there is deliberately no lookup by order id alone. */
	Optional<KitchenOrder> findByRestaurantIdAndOrderId(UUID restaurantId, UUID orderId);

	/**
	 * Whether an accepted-order event id has been stored at all. The one deliberate lookup across restaurants: the id
	 * is unique in the whole table, and only yes or no is returned, never another restaurant's data.
	 */
	boolean existsBySourceEventId(UUID sourceEventId);

	/** Whether one of these line ids is already stored for some order of the restaurant. */
	@Query("SELECT count(i) > 0 FROM KitchenOrderItem i WHERE i.restaurantId = :restaurantId AND i.orderItemId IN :lineIds")
	boolean existsLineId(@Param("restaurantId") UUID restaurantId, @Param("lineIds") Collection<UUID> lineIds);

	/** Whether this restaurant's order was stored from exactly this event. */
	boolean existsByRestaurantIdAndOrderIdAndSourceEventId(UUID restaurantId, UUID orderId, UUID sourceEventId);

	/** Day report: one row per CURRENT status of the orders received in [from, to): {status, number of orders}. */
	@Query("""
			select o.status, count(o) from KitchenOrder o
			where o.restaurantId = :restaurantId and o.receivedAt >= :from and o.receivedAt < :to
			group by o.status
			""")
	List<Object[]> countReceivedByStatus(@Param("restaurantId") UUID restaurantId, @Param("from") Instant from, @Param("to") Instant to);

	/** Day report: orders that BECAME ready inside [from, to), whenever they were received. */
	long countByRestaurantIdAndReadyAtGreaterThanEqualAndReadyAtLessThan(UUID restaurantId, Instant from, Instant to);

	/** Day report: orders that were cancelled inside [from, to), whenever they were received. */
	long countByRestaurantIdAndCancelledAtGreaterThanEqualAndCancelledAtLessThan(UUID restaurantId, Instant from, Instant to);

	/**
	 * Tenant scoped lookup that takes a row lock (SELECT ... FOR UPDATE) so concurrent preparation commands on the
	 * same order are serialized. The lock wait is bounded; a timeout surfaces as a locking failure (409).
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@QueryHints(@QueryHint(name = RowLocks.TIMEOUT_HINT, value = RowLocks.TIMEOUT_MILLIS))
	@Query("select o from KitchenOrder o where o.restaurantId = :restaurantId and o.orderId = :orderId")
	Optional<KitchenOrder> findForUpdate(@Param("restaurantId") UUID restaurantId, @Param("orderId") UUID orderId);
}

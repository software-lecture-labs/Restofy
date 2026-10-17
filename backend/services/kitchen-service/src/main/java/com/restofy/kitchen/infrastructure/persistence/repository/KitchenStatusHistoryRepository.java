package com.restofy.kitchen.infrastructure.persistence.repository;

import com.restofy.kitchen.domain.entity.KitchenStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface KitchenStatusHistoryRepository extends JpaRepository<KitchenStatusHistory, UUID> {

	List<KitchenStatusHistory> findByRestaurantIdAndKitchenOrderIdOrderByOccurredAtAsc(UUID restaurantId,
			UUID kitchenOrderId);
}

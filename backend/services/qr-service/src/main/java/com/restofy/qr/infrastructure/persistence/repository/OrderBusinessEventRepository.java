package com.restofy.qr.infrastructure.persistence.repository;

import com.restofy.qr.domain.entity.OrderBusinessEvent;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderBusinessEventRepository extends JpaRepository<OrderBusinessEvent, UUID> {
    Optional<OrderBusinessEvent> findByRestaurantIdAndEventId(UUID restaurantId, UUID eventId);
}

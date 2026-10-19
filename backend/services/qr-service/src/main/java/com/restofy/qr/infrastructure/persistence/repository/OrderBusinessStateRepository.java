package com.restofy.qr.infrastructure.persistence.repository;

import com.restofy.qr.domain.entity.OrderBusinessState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderBusinessStateRepository extends JpaRepository<OrderBusinessState, UUID> {
    Optional<OrderBusinessState> findByRestaurantIdAndAdmissionId(UUID restaurantId, UUID admissionId);
    List<OrderBusinessState> findByRestaurantIdAndTableSessionId(UUID restaurantId, UUID sessionId);
}

package com.restofy.qr.infrastructure.persistence.repository;

import com.restofy.qr.domain.entity.OrderAdmission;
import com.restofy.qr.domain.enums.OrderAdmissionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OrderAdmissionRepository extends JpaRepository<OrderAdmission, UUID> {
    Optional<OrderAdmission> findByRestaurantIdAndGuestSessionIdAndIdempotencyKey(UUID restaurantId, UUID guestSessionId, String key);
    Optional<OrderAdmission> findByRestaurantIdAndId(UUID restaurantId, UUID id);

    @Query("select a.tableSessionId from OrderAdmission a where a.restaurantId=:restaurantId and a.id=:id")
    Optional<UUID> locateSessionId(UUID restaurantId, UUID id);

    @Query("select distinct a.guestSessionId from OrderAdmission a where a.restaurantId=:restaurantId "
            + "and a.tableSessionId=:sessionId and a.status=:status and (a.confirmedAt is null or a.confirmedAt<=:now)")
    List<UUID> findGuestIds(UUID restaurantId, UUID sessionId, OrderAdmissionStatus status, Instant now);
}

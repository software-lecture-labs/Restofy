package com.restofy.waiter.infrastructure.persistence.repository;

import com.restofy.waiter.domain.entity.ServiceRequest;
import com.restofy.waiter.domain.enums.ServiceRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ServiceRequestRepository extends JpaRepository<ServiceRequest, UUID> {

    /**
     * Calls of the given live table sessions, always scoped to the restaurant. Because the sessions are the
     * current ones reported by QR, calls of an earlier session of the same table never show up here.
     */
    List<ServiceRequest> findByRestaurantIdAndTableSessionIdInAndStatusInOrderByCreatedAtAscIdAsc(
            UUID restaurantId, Collection<UUID> tableSessionIds, Collection<ServiceRequestStatus> statuses);
}

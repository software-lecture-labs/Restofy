package com.restofy.waiter.infrastructure.persistence.repository;

import com.restofy.waiter.domain.entity.ServiceRequest;
import com.restofy.waiter.domain.enums.ServiceRequestStatus;
import com.restofy.waiter.domain.enums.ServiceRequestType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceRequestRepository
        extends JpaRepository<ServiceRequest, UUID>, JpaSpecificationExecutor<ServiceRequest> {

    /**
     * Calls of the given live table sessions, always scoped to the restaurant. Because the sessions are the
     * current ones reported by QR, calls of an earlier session of the same table never show up here.
     */
    List<ServiceRequest> findByRestaurantIdAndTableSessionIdInAndStatusInOrderByCreatedAtAscIdAsc(
            UUID restaurantId, Collection<UUID> tableSessionIds, Collection<ServiceRequestStatus> statuses);

    /** Tenant-scoped lookup: another restaurant's call id is indistinguishable from a missing one. */
    Optional<ServiceRequest> findByIdAndRestaurantId(UUID id, UUID restaurantId);

    /** The guest's active call of that type, if any; at most one exists (partial unique index). */
    Optional<ServiceRequest> findFirstByRestaurantIdAndGuestSessionIdAndRequestTypeAndStatusIn(
            UUID restaurantId, UUID guestSessionId, ServiceRequestType requestType,
            Collection<ServiceRequestStatus> statuses);
}

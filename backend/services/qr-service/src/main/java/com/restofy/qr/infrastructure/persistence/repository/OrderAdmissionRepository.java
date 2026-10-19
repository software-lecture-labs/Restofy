package com.restofy.qr.infrastructure.persistence.repository;

import com.restofy.qr.domain.entity.OrderAdmission;
import com.restofy.qr.domain.enums.OrderAdmissionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OrderAdmissionRepository extends JpaRepository<OrderAdmission, UUID> {
    Optional<OrderAdmission> findByRestaurantIdAndGuestSessionIdAndIdempotencyKey(UUID restaurantId, UUID guestSessionId, String key);
    Optional<OrderAdmission> findByRestaurantIdAndId(UUID restaurantId, UUID id);
    List<OrderAdmission> findByRestaurantIdAndTableSessionIdAndStatusIn(UUID restaurantId, UUID sessionId, Collection<OrderAdmissionStatus> statuses);

    @Query("select distinct a.guestSessionId from OrderAdmission a where a.restaurantId=:restaurantId and a.tableSessionId=:sessionId "
            + "and a.status=com.restofy.qr.domain.enums.OrderAdmissionStatus.CONFIRMED and a.confirmedAt<=:now "
            + "and (not exists (select b.admissionId from OrderBusinessState b where b.restaurantId=a.restaurantId and b.admissionId=a.id) "
            + "or exists (select b.admissionId from OrderBusinessState b where b.restaurantId=a.restaurantId and b.admissionId=a.id "
            + "and (b.status<>com.restofy.qr.domain.enums.OrderBusinessStatus.CANCELLED or b.idleReleasedAt is null)))")
    List<UUID> findProtectedGuestIds(UUID restaurantId, UUID sessionId, Instant now);

    @Query("select distinct b.guestSessionId from OrderBusinessState b where b.restaurantId=:restaurantId and b.tableSessionId=:sessionId "
            + "and (b.financialOutcome=com.restofy.qr.domain.enums.FinancialOutcome.RECONCILIATION_REQUIRED "
            + "or (b.status=com.restofy.qr.domain.enums.OrderBusinessStatus.CANCELLED "
            + "and b.financialOutcome<>com.restofy.qr.domain.enums.FinancialOutcome.CANCELLATION_CLEAR) "
            + "or (b.status=com.restofy.qr.domain.enums.OrderBusinessStatus.COMPLETED "
            + "and b.financialOutcome<>com.restofy.qr.domain.enums.FinancialOutcome.SETTLED))")
    List<UUID> findReconciliationGuestIds(UUID restaurantId, UUID sessionId);

    @Query("select a.tableSessionId from OrderAdmission a where a.restaurantId=:restaurantId and a.id=:id")
    Optional<UUID> locateSessionId(UUID restaurantId, UUID id);

    @Query("select distinct a.guestSessionId from OrderAdmission a where a.restaurantId=:restaurantId "
            + "and a.tableSessionId=:sessionId and a.status=:status and (a.confirmedAt is null or a.confirmedAt<=:now)")
    List<UUID> findGuestIds(UUID restaurantId, UUID sessionId, OrderAdmissionStatus status, Instant now);
}

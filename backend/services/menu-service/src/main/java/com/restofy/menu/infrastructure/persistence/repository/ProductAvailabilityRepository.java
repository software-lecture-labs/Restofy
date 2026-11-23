package com.restofy.menu.infrastructure.persistence.repository;

import com.restofy.menu.domain.entity.ProductAvailability;
import com.restofy.menu.domain.enums.AvailabilityStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every query carries the restaurant id; the inherited findById/findAll are not tenant-scoped and must
 * not be used by services. A missing row is a normal answer: the product is then not orderable.
 */
public interface ProductAvailabilityRepository extends JpaRepository<ProductAvailability, UUID> {

    Optional<ProductAvailability> findByRestaurantIdAndProductId(UUID restaurantId, UUID productId);

    /**
     * The same row, locked for update until the transaction ends: commands for one product are applied one at a time,
     * in the order they obtain the lock, instead of racing on the version.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ProductAvailability a where a.restaurantId = :restaurantId and a.productId = :productId")
    Optional<ProductAvailability> findLockedByRestaurantIdAndProductId(@Param("restaurantId") UUID restaurantId,
            @Param("productId") UUID productId);

    /** Batch lookup for a whole product page (no per-row queries). */
    List<ProductAvailability> findByRestaurantIdAndProductIdIn(UUID restaurantId, Collection<UUID> productIds);

    /**
     * The products of ONE restaurant whose current availability is one of {@code statuses}, newest closing first, then by
     * product id so that paging is deterministic. Both sides carry the restaurant id. Read only; the {@code Pageable} must
     * be unsorted (the order is part of the query).
     */
    @Query(value = "select new com.restofy.menu.infrastructure.persistence.repository.ClosedProductRow(p.id, p.name, "
            + "p.active, p.published, a.status, a.reasonCode, a.reasonText, a.changedAt, a.changedByUserId, a.version) "
            + "from ProductAvailability a, Product p where a.restaurantId = :restaurantId and p.restaurantId = :restaurantId "
            + "and p.id = a.productId and a.status in :statuses order by a.changedAt desc, p.id asc",
            countQuery = "select count(a) from ProductAvailability a, Product p where a.restaurantId = :restaurantId "
                    + "and p.restaurantId = :restaurantId and p.id = a.productId and a.status in :statuses")
    Page<ClosedProductRow> findByStatuses(@Param("restaurantId") UUID restaurantId,
            @Param("statuses") Collection<AvailabilityStatus> statuses, Pageable pageable);
}

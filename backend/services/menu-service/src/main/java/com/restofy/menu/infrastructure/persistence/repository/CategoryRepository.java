package com.restofy.menu.infrastructure.persistence.repository;

import com.restofy.menu.domain.entity.Category;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The queries declared here all carry the restaurant id. The inherited findById/findAll are not
 * tenant-scoped: services must use the declared methods instead.
 */
public interface CategoryRepository extends JpaRepository<Category, UUID> {

    /** Tenant-scoped lookup. Never load a category by id alone. */
    Optional<Category> findByIdAndRestaurantId(UUID id, UUID restaurantId);

    Page<Category> findByRestaurantIdAndParentIdIsNull(UUID restaurantId, Pageable pageable);

    Page<Category> findByRestaurantIdAndParentIdIsNullAndActive(UUID restaurantId, boolean active, Pageable pageable);

    Page<Category> findByRestaurantIdAndParentId(UUID restaurantId, UUID parentId, Pageable pageable);

    Page<Category> findByRestaurantIdAndParentIdAndActive(UUID restaurantId, UUID parentId, boolean active, Pageable pageable);

    /** Customer-menu read model input: only active rows, already in display order. */
    List<Category> findByRestaurantIdAndActiveTrueOrderBySortOrderAscNameAscIdAsc(UUID restaurantId);
}

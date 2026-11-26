package com.restofy.qr.infrastructure.persistence.repository;

import com.restofy.qr.domain.entity.TableSessionClosureEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TableSessionClosureEventRepository extends JpaRepository<TableSessionClosureEvent, UUID> {
    List<TableSessionClosureEvent> findByRestaurantIdAndEventSeqGreaterThanOrderByEventSeqAsc(
            UUID restaurantId, long afterSeq, Pageable page);
}

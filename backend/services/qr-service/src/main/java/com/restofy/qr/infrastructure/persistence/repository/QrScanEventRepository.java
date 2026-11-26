package com.restofy.qr.infrastructure.persistence.repository;

import com.restofy.qr.domain.entity.QrScanEvent;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface QrScanEventRepository extends JpaRepository<QrScanEvent, UUID> {
    long countByRestaurantIdAndTableId(UUID restaurantId, UUID tableId);
    long countByRestaurantIdAndTableSessionId(UUID restaurantId, UUID tableSessionId);

    @Query("select e.tableSessionId as tableSessionId, count(e) as scans from QrScanEvent e "
            + "where e.restaurantId = :restaurantId and e.tableSessionId in :sessionIds group by e.tableSessionId")
    List<SessionScans> countScansBySession(UUID restaurantId, Collection<UUID> sessionIds);

    interface SessionScans {
        UUID getTableSessionId();
        long getScans();
    }
}

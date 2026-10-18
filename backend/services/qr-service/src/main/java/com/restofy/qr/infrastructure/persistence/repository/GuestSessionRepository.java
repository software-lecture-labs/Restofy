package com.restofy.qr.infrastructure.persistence.repository;

import com.restofy.qr.domain.entity.GuestSession;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface GuestSessionRepository extends JpaRepository<GuestSession, UUID> {
    // Only immutable IDs before locking; do not cache mutable guest/parent state here.
    @Query("select g.id as id, g.restaurantId as restaurantId, g.tableSessionId as tableSessionId from GuestSession g where g.tokenDigest = :digest")
    Optional<GuestLocation> locateByDigest(String digest);

    Optional<GuestSession> findByRestaurantIdAndTableSessionIdAndId(UUID restaurantId, UUID tableSessionId, UUID id);

    interface GuestLocation {
        UUID getId();
        UUID getRestaurantId();
        UUID getTableSessionId();
    }
}

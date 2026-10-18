package com.restofy.qr.infrastructure.persistence.repository;

import com.restofy.qr.domain.entity.QrCode;
import com.restofy.qr.domain.enums.QrCodeStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface QrCodeRepository extends JpaRepository<QrCode, UUID> {
    boolean existsByRestaurantIdAndTableIdAndStatus(UUID restaurantId, UUID tableId, QrCodeStatus status);

    Optional<QrCode> findByRestaurantIdAndTableIdAndId(UUID restaurantId, UUID tableId, UUID id);

    Page<QrCode> findByRestaurantIdAndTableId(UUID restaurantId, UUID tableId, Pageable pageable);
}

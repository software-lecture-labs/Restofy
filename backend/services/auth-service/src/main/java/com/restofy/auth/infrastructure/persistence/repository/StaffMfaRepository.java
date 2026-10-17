package com.restofy.auth.infrastructure.persistence.repository;

import java.util.Optional;
import java.util.UUID;
import com.restofy.auth.domain.entity.StaffMfa;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;

public interface StaffMfaRepository extends JpaRepository<StaffMfa, UUID> {
    Optional<StaffMfa> findByStaffUserId(UUID userId);
    boolean existsByStaffUserIdAndEnabledAtIsNotNull(UUID userId);

    @Modifying
    @Query("update StaffMfa mfa set mfa.lastAcceptedCounter = :counter, mfa.updatedAt = :now where mfa.id = :id and mfa.enabledAt is not null and mfa.lastAcceptedCounter < :counter")
    int advance(UUID id, long counter, Instant now);
}

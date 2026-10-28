package com.restofy.auth.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.restofy.auth.domain.entity.MfaRecoveryCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface MfaRecoveryCodeRepository extends JpaRepository<MfaRecoveryCode, UUID> {
    List<MfaRecoveryCode> findByStaffUserIdAndRevokedAtIsNull(UUID userId);

    @Modifying
    @Query("update MfaRecoveryCode code set code.usedAt = :now where code.id = :id and code.usedAt is null and code.revokedAt is null")
    int consume(UUID id, Instant now);

    @Modifying
    @Query("update MfaRecoveryCode code set code.revokedAt = :now where code.staffUserId = :userId and code.revokedAt is null")
    int revoke(UUID userId, Instant now);
}

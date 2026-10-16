package com.restofy.auth.infrastructure.persistence.repository;

import java.util.Optional;
import java.util.UUID;

import com.restofy.auth.domain.entity.StaffUser;
import com.restofy.auth.domain.enums.StaffRole;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StaffUserRepository extends JpaRepository<StaffUser, UUID> {
    Optional<StaffUser> findByEmailNormalized(String emailNormalized);

    boolean existsByRole(StaffRole role);
}

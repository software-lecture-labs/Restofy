package com.restofy.auth.infrastructure.persistence.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.restofy.auth.domain.entity.StaffUser;
import com.restofy.auth.domain.enums.StaffRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StaffUserRepository extends JpaRepository<StaffUser, UUID> {
    @Query(value = "SELECT id FROM staff_users WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<UUID> lockIdentity(UUID id);
    Optional<StaffUser> findByEmailNormalized(String emailNormalized);

    boolean existsByRole(StaffRole role);

    @Query(value = "SELECT id FROM staff_users WHERE id IN (:ids) ORDER BY id FOR UPDATE", nativeQuery = true)
    List<UUID> lockIdentities(List<UUID> ids);

    Page<StaffUser> findByRoleAndActiveTrue(StaffRole role, Pageable pageable);
}

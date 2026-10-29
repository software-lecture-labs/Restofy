package com.restofy.auth.infrastructure.persistence.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.restofy.auth.domain.entity.UserSession;
import com.restofy.auth.domain.enums.SessionAuthState;
import com.restofy.auth.domain.enums.StaffRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {
    List<UserSession> findByStaffUserId(UUID staffUserId);

    @Query("""
            select session from UserSession session, StaffUser staff
            where session.staffUserId = staff.id and staff.id = :userId and session.id = :sessionId
                and staff.active = true and staff.role = :role and session.roleSnapshot = :role
                and session.authState = :state
            """)
    Optional<UserSession> readIdentity(UUID userId, UUID sessionId, StaffRole role, SessionAuthState state);
}

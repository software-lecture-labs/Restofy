package com.restofy.auth.infrastructure.persistence.repository;

import java.util.List;
import java.util.UUID;

import com.restofy.auth.domain.entity.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {
    List<UserSession> findByStaffUserId(UUID staffUserId);
}

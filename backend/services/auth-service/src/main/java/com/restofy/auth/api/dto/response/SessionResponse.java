package com.restofy.auth.api.dto.response;

import java.time.Instant;

import com.restofy.auth.domain.entity.UserSession;
import com.restofy.auth.domain.enums.SessionAuthState;
import com.restofy.auth.domain.enums.StaffRole;

public record SessionResponse(SessionAuthState status, StaffRole role, Instant idleExpiresAt, Instant absoluteExpiresAt) {
    public static SessionResponse from(UserSession session) {
        return new SessionResponse(session.getAuthState(), session.getRoleSnapshot(), session.idleExpiresAt(), session.getAbsoluteExpiresAt());
    }
}

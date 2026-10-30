package com.restofy.auth.infrastructure.security;

import java.time.Clock;
import java.time.Instant;
import java.util.function.BiFunction;

import com.restofy.auth.application.service.StaffSessionService;
import com.restofy.auth.domain.entity.StaffMfa;
import com.restofy.auth.domain.entity.UserSession;
import com.restofy.auth.domain.enums.StaffRole;
import com.restofy.auth.domain.policy.MfaPolicy;
import com.restofy.auth.infrastructure.persistence.repository.StaffMfaRepository;
import com.restofy.auth.infrastructure.security.LoginRateLimiter.LoginLimitException;
import com.restofy.auth.infrastructure.security.MfaAuditLogger.Event;
import com.restofy.auth.infrastructure.security.StaffJwtTokens.Identity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MfaAccountAttempts {
    private final StaffSessionService sessions;
    private final StaffMfaRepository records;
    private final MfaAuditLogger audit;
    private final Clock clock;

    public MfaAccountAttempts(StaffSessionService sessions, StaffMfaRepository records,
            MfaAuditLogger audit, Clock clock) {
        this.sessions = sessions;
        this.records = records;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = {BadCredentialsException.class, LoginLimitException.class})
    public <T> T attempt(Identity identity, BiFunction<StaffMfa, UserSession, T> operation) {
        if (identity.role() != StaffRole.ADMIN) {
            throw new AccessDeniedException("MFA requires ADMIN");
        }
        UserSession session = sessions.authenticate(identity);
        Instant now = clock.instant();
        StaffMfa mfaState = records.findByStaffUserId(identity.userId())
                .orElseGet(() -> records.saveAndFlush(new StaffMfa(identity.userId(), now)));
        if (mfaState.getLockedUntil() != null && now.isBefore(mfaState.getLockedUntil())) {
            throw new LoginLimitException(Math.max(1, mfaState.getLockedUntil().getEpochSecond() - now.getEpochSecond()));
        }
        try {
            return operation.apply(mfaState, session);
        } catch (BadCredentialsException exception) {
            mfaState.failed(clock.instant());
            records.saveAndFlush(mfaState);
            audit.publish(Event.VERIFICATION_FAILED, identity.userId(), identity.sessionId());
            if (mfaState.getFailedAttempts() == MfaPolicy.FAILURES_BEFORE_LOCK) {
                audit.publish(Event.LOCKED, identity.userId(), identity.sessionId());
                throw new LoginLimitException(MfaPolicy.LOCK_DURATION.toSeconds());
            }
            throw exception;
        } catch (MfaUnavailableException exception) {
            audit.cipherFailure(identity.userId(), identity.sessionId());
            throw exception;
        }
    }
}

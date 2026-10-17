package com.restofy.auth.infrastructure.security;

import java.time.Clock;
import java.time.Instant;
import java.util.function.BiFunction;
import com.restofy.auth.application.service.StaffSessionService;
import com.restofy.auth.domain.entity.StaffMfa;
import com.restofy.auth.domain.entity.UserSession;
import com.restofy.auth.domain.enums.StaffRole;
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
public class MfaAttemptLimiter {
    private final StaffSessionService sessions;
    private final StaffMfaRepository records;
    private final MfaPeerLimiter peers;
    private final MfaAuditLogger audit;
    private final Clock clock;

    public MfaAttemptLimiter(StaffSessionService sessions, StaffMfaRepository records, MfaPeerLimiter peers,
            MfaAuditLogger audit, Clock clock) {
        this.sessions = sessions;
        this.records = records;
        this.peers = peers;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = {BadCredentialsException.class, LoginLimitException.class})
    public <T> T attempt(Identity identity, String peer, BiFunction<StaffMfa, UserSession, T> operation) {
        peers.check(peer);
        if (identity.role() != StaffRole.ADMIN) { throw new AccessDeniedException("MFA requires ADMIN"); }
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
            if (mfaState.getFailedAttempts() == 5) {
                audit.publish(Event.LOCKED, identity.userId(), identity.sessionId());
                throw new LoginLimitException(300);
            }
            throw exception;
        } catch (MfaUnavailableException exception) {
            audit.cipherFailure(identity.userId(), identity.sessionId());
            throw exception;
        }
    }
}

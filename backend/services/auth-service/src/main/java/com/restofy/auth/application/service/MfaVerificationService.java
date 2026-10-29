package com.restofy.auth.application.service;

import java.time.Clock;
import java.util.List;

import com.restofy.auth.domain.entity.MfaRecoveryCode;
import com.restofy.auth.domain.enums.SessionAuthState;
import com.restofy.auth.infrastructure.persistence.repository.StaffUserRepository;
import com.restofy.auth.infrastructure.security.MfaAttemptLimiter;
import com.restofy.auth.infrastructure.security.MfaAuditLogger;
import com.restofy.auth.infrastructure.security.MfaAuditLogger.Event;
import com.restofy.auth.infrastructure.security.StaffJwtTokens.Identity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

@Service
public class MfaVerificationService {
    private final MfaAttemptLimiter attempts;
    private final MfaFactors factors;
    private final StaffUserRepository users;
    private final StaffPasswordVerification passwords;
    private final StaffSessionService sessions;
    private final MfaAuditLogger audit;
    private final Clock clock;

    public MfaVerificationService(MfaAttemptLimiter attempts, MfaFactors factors, StaffUserRepository users,
            StaffPasswordVerification passwords, StaffSessionService sessions, MfaAuditLogger audit, Clock clock) {
        this.attempts = attempts;
        this.factors = factors;
        this.users = users;
        this.passwords = passwords;
        this.sessions = sessions;
        this.audit = audit;
        this.clock = clock;
    }

    public Completion confirm(Identity identity, String code, String peer) {
        return attempts.attempt(identity, peer, (mfaState, session) -> {
            requireState(identity, SessionAuthState.MFA_PENDING);
            if (mfaState.enabled() || !identity.sessionId().equals(mfaState.getPendingSessionId())
                    || mfaState.getPendingExpiresAt() == null || !clock.instant().isBefore(mfaState.getPendingExpiresAt())) {
                throw invalid();
            }
            long counter = factors.verifiedCounter(mfaState, code);
            var grant = sessions.completeMfa(identity);
            mfaState.enable(counter, clock.instant());
            mfaState.clearFailures(clock.instant());
            List<String> generated = factors.newCodes(identity.userId());
            audit.publish(Event.ENROLL_CONFIRMED, identity.userId(), identity.sessionId());
            return new Completion(grant, generated);
        });
    }

    public Completion verify(Identity identity, String code, String peer) {
        return attempts.attempt(identity, peer, (mfaState, session) -> {
            requireState(identity, SessionAuthState.MFA_PENDING);
            factors.requireEnabled(mfaState);
            long counter = factors.verifiedCounter(mfaState, code);
            var grant = sessions.completeMfa(identity);
            factors.advance(mfaState, counter);
            mfaState.clearFailures(clock.instant());
            audit.publish(Event.VERIFIED, identity.userId(), identity.sessionId());
            return new Completion(grant, List.of());
        });
    }

    public Completion recover(Identity identity, String code, String peer) {
        return attempts.attempt(identity, peer, (mfaState, session) -> {
            requireState(identity, SessionAuthState.MFA_PENDING);
            factors.requireEnabled(mfaState);
            MfaRecoveryCode matched = factors.findCode(identity.userId(), code);
            var grant = sessions.completeMfa(identity);
            factors.consume(matched);
            mfaState.clearFailures(clock.instant());
            audit.publish(Event.RECOVERED, identity.userId(), identity.sessionId());
            return new Completion(grant, List.of());
        });
    }

    public List<String> regenerate(Identity identity, String password, String totpCode, String recoveryCode, String peer) {
        return attempts.attempt(identity, peer, (mfaState, session) -> {
            requireState(identity, SessionAuthState.AUTHENTICATED);
            factors.requireEnabled(mfaState);
            var user = users.findById(identity.userId()).orElseThrow(MfaVerificationService::invalid);
            passwords.verify(user.getEmailNormalized(), password);
            if ((totpCode == null) == (recoveryCode == null)) {
                throw invalid();
            }
            Long counter = totpCode == null ? null : factors.verifiedCounter(mfaState, totpCode);
            MfaRecoveryCode matched = recoveryCode == null ? null : factors.findCode(identity.userId(), recoveryCode);
            sessions.revokeOtherSessions(identity);
            if (counter != null) {
                factors.advance(mfaState, counter);
            }
            else {
                factors.consume(matched);
            }
            factors.revokeCodes(identity.userId());
            mfaState.clearFailures(clock.instant());
            List<String> generated = factors.newCodes(identity.userId());
            audit.publish(Event.CODES_REGENERATED, identity.userId(), identity.sessionId());
            return generated;
        });
    }

    private static void requireState(Identity identity, SessionAuthState state) {
        if (identity.state() != state) {
            throw new AccessDeniedException("Invalid MFA scope");
        }
    }

    private static BadCredentialsException invalid() {

        return new BadCredentialsException("Invalid credentials");
    }

    public record Completion(StaffSessionService.Grant grant, List<String> recoveryCodes) {
        @Override public String toString() {
            return "MFA completion";
        }
    }
}

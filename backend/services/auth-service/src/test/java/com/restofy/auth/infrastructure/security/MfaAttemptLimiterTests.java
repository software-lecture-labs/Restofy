package com.restofy.auth.infrastructure.security;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

import com.restofy.auth.application.service.StaffSessionService;
import com.restofy.auth.domain.entity.StaffMfa;
import com.restofy.auth.domain.entity.UserSession;
import com.restofy.auth.domain.enums.SessionAuthState;
import com.restofy.auth.domain.enums.StaffRole;
import com.restofy.auth.infrastructure.persistence.repository.StaffMfaRepository;
import com.restofy.auth.infrastructure.security.LoginRateLimiter.LoginLimitException;
import com.restofy.auth.infrastructure.security.StaffJwtTokens.Identity;
import com.restofy.auth.support.MutableAuthClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MfaAttemptLimiterTests {
    private final MutableAuthClock clock = new MutableAuthClock();
    private final StaffSessionService sessions = mock(StaffSessionService.class);
    private final StaffMfaRepository records = mock(StaffMfaRepository.class);
    private final MfaAuditLogger audit = mock(MfaAuditLogger.class);
    private final MfaAccountAttempts limiter = new MfaAccountAttempts(sessions, records, audit, clock);
    private final Identity identity = new Identity(UUID.randomUUID(), UUID.randomUUID(), StaffRole.ADMIN, SessionAuthState.MFA_PENDING);
    private final StaffMfa mfaState = new StaffMfa(identity.userId(), clock.instant());

    @BeforeEach
    void setup() {
        when(records.findByStaffUserId(identity.userId())).thenReturn(Optional.of(mfaState));
        UserSession session = mock(UserSession.class);
        when(sessions.authenticate(identity)).thenReturn(session);
    }

    @Test
    void sharedCounterLocksBeforeNextVerificationAndExpires() {
        for (int index = 0; index < 4; index++) {
            assertThatThrownBy(this::fail).isInstanceOf(BadCredentialsException.class);
        }
        assertThatThrownBy(this::fail).isInstanceOf(LoginLimitException.class);
        AtomicBoolean invoked = new AtomicBoolean();
        assertThatThrownBy(() -> limiter.attempt(identity, (mfa, session) -> {
            invoked.set(true);
            return true;
        })).isInstanceOf(LoginLimitException.class);
        assertThat(invoked).isFalse();
        assertThat(mfaState.getFailedAttempts()).isEqualTo(5);
        verify(records, times(5)).saveAndFlush(mfaState);
        clock.advance(300);
        boolean allowed = limiter.attempt(identity, (mfa, session) -> true);
        assertThat(allowed).isTrue();
        assertThatThrownBy(this::fail).isInstanceOf(BadCredentialsException.class);
        assertThat(mfaState.getFailedAttempts()).isEqualTo(1);
    }

    @Test
    void unavailableAndForbiddenOperationsAreNotCredentialFailures() {
        assertThatThrownBy(() -> limiter.attempt(identity, (mfa, session) -> {
            throw new MfaUnavailableException();
        })).isInstanceOf(MfaUnavailableException.class);
        assertThat(mfaState.getFailedAttempts()).isZero();
        verify(audit).cipherFailure(identity.userId(), identity.sessionId());
        Identity waiter = new Identity(UUID.randomUUID(), UUID.randomUUID(), StaffRole.WAITER, SessionAuthState.AUTHENTICATED);
        assertThatThrownBy(() -> limiter.attempt(waiter, (mfa, session) -> true))
                .isInstanceOf(AccessDeniedException.class);
        verify(sessions, never()).authenticate(waiter);
    }

    @Test
    void transactionBoundaryCommits401And429IndependentlyOfCaller() throws Exception {
        Transactional boundary = MfaAccountAttempts.class.getMethod("attempt", Identity.class,
                BiFunction.class).getAnnotation(Transactional.class);
        assertThat(boundary.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(boundary.noRollbackFor()).contains(BadCredentialsException.class, LoginLimitException.class);
    }

    private void fail() {
        limiter.attempt(identity, (mfa, session) -> { throw new BadCredentialsException("Invalid credentials");
        });
    }
}

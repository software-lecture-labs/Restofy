package com.restofy.auth.application.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import com.restofy.auth.domain.entity.*;
import com.restofy.auth.domain.enums.*;
import com.restofy.auth.infrastructure.persistence.repository.StaffUserRepository;
import com.restofy.auth.infrastructure.security.*;
import com.restofy.auth.infrastructure.security.MfaAuditLogger.Event;
import com.restofy.auth.infrastructure.security.StaffJwtTokens.Identity;
import com.restofy.auth.support.MutableAuthClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MfaVerificationTests {
    private final MutableAuthClock clock = new MutableAuthClock();
    private final MfaAttemptLimiter attempts = mock(MfaAttemptLimiter.class);
    private final MfaFactors factors = mock(MfaFactors.class);
    private final StaffUserRepository users = mock(StaffUserRepository.class);
    private final StaffPasswordVerification passwords = mock(StaffPasswordVerification.class);
    private final StaffSessionService sessions = mock(StaffSessionService.class);
    private final MfaAuditLogger audit = mock(MfaAuditLogger.class);
    private final MfaVerificationService service = new MfaVerificationService(attempts, factors, users, passwords, sessions, audit, clock);
    private final UUID userId = UUID.randomUUID(), sessionId = UUID.randomUUID();
    private final Identity pending = new Identity(userId, sessionId, StaffRole.ADMIN, SessionAuthState.MFA_PENDING);
    private final Identity full = new Identity(userId, sessionId, StaffRole.ADMIN, SessionAuthState.AUTHENTICATED);
    private final StaffMfa mfaState = new StaffMfa(userId, clock.instant());

    @BeforeEach
    void setup() {
        when(attempts.attempt(any(), anyString(), any())).thenAnswer(invocation -> {
            BiFunction<StaffMfa, UserSession, Object> operation = invocation.getArgument(2);
            return operation.apply(mfaState, mock(UserSession.class));
        });
        mfaState.enroll(new byte[48], sessionId, clock.instant(), clock.instant().plusSeconds(300));
        when(factors.verifiedCounter(any(), anyString())).thenReturn(123L);
        when(factors.newCodes(userId)).thenReturn(List.of("test-only-code"));
        when(sessions.completeMfa(pending)).thenReturn(new StaffSessionService.Grant(mock(UserSession.class), "test-only-access", "test-only-refresh"));
    }

    @Test
    void confirmationEnablesFactorAndReturnsCodesAfterPromotion() {
        var result = service.confirm(pending, "123456", "peer");
        assertThat(mfaState.enabled()).isTrue();
        assertThat(mfaState.getLastAcceptedCounter()).isEqualTo(123);
        assertThat(result.recoveryCodes()).containsExactly("test-only-code");
        verify(sessions).completeMfa(pending);
        verify(audit).publish(Event.ENROLL_CONFIRMED, userId, sessionId);
        assertThatThrownBy(() -> service.confirm(pending, "123456", "peer")).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void confirmationRejectsWrongOwnerExpiredAndFullScopeBeforeFactor() {
        Identity other = new Identity(userId, UUID.randomUUID(), StaffRole.ADMIN, SessionAuthState.MFA_PENDING);
        assertThatThrownBy(() -> service.confirm(other, "123456", "peer")).isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> service.confirm(full, "123456", "peer")).isInstanceOf(AccessDeniedException.class);
        clock.advance(300);
        assertThatThrownBy(() -> service.confirm(pending, "123456", "peer")).isInstanceOf(BadCredentialsException.class);
        verifyNoInteractions(factors);
    }

    @Test
    void loginFactorsPromoteWithoutReturningNewRecoverySets() {
        mfaState.enable(122, clock.instant());
        assertThat(service.verify(pending, "123456", "peer").recoveryCodes()).isEmpty();
        verify(factors).advance(mfaState, 123);
        MfaRecoveryCode recovery = mock(MfaRecoveryCode.class);
        when(factors.findCode(userId, "recovery")).thenReturn(recovery);
        assertThat(service.recover(pending, "recovery", "peer").recoveryCodes()).isEmpty();
        verify(factors).consume(recovery);
        verify(audit).publish(Event.RECOVERED, userId, sessionId);
        assertThatThrownBy(() -> service.verify(full, "123456", "peer")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void regenerationValidatesAllProofsBeforeMutatingFactors() {
        mfaState.enable(122, clock.instant());
        StaffUser user = mock(StaffUser.class);
        when(user.getEmailNormalized()).thenReturn("admin@example.com");
        when(users.findById(userId)).thenReturn(Optional.of(user));
        assertThat(service.regenerate(full, "test-only-password", "123456", null, "peer")).containsExactly("test-only-code");
        var order = inOrder(sessions, factors);
        order.verify(factors).requireEnabled(mfaState);
        order.verify(factors).verifiedCounter(mfaState, "123456");
        order.verify(sessions).revokeOtherSessions(full);
        order.verify(factors).advance(mfaState, 123);
        order.verify(factors).revokeCodes(userId);
        assertThatThrownBy(() -> service.regenerate(full, "test-only-password", null, null, "peer"))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> service.regenerate(full, "test-only-password", "123456", "recovery", "peer"))
                .isInstanceOf(BadCredentialsException.class);
        MfaRecoveryCode recovery = mock(MfaRecoveryCode.class);
        when(factors.findCode(userId, "recovery")).thenReturn(recovery);
        service.regenerate(full, "test-only-password", null, "recovery", "peer");
        verify(factors).consume(recovery);
        when(passwords.verify("admin@example.com", "wrong")).thenThrow(new BadCredentialsException("Invalid credentials"));
        assertThatThrownBy(() -> service.regenerate(full, "wrong", "123456", null, "peer")).isInstanceOf(BadCredentialsException.class);
    }
}

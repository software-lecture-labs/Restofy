package com.restofy.auth.infrastructure.security;

import java.time.Instant;
import java.util.UUID;
import com.restofy.auth.domain.entity.StaffMfa;
import com.restofy.auth.domain.entity.StaffUser;
import com.restofy.auth.domain.entity.UserSession;
import com.restofy.auth.domain.enums.SessionAuthState;
import com.restofy.auth.domain.enums.StaffRole;
import com.restofy.auth.infrastructure.security.LoginRateLimiter.LoginLimitException;
import com.restofy.auth.support.MutableAuthClock;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MfaStateTests {
    private static final String HASH = new StaffPasswords().hash("Mfa-test-password-01");

    @Test
    void promotionPreservesIdentityAndPasswordLoginAbsoluteDeadline() {
        Instant created = Instant.parse("2026-10-17T10:00:00Z");
        UserSession session = new UserSession(new StaffUser("admin@example.com", HASH, StaffRole.ADMIN), created, 604800, "test");
        UUID original = session.getId();
        Instant promoted = created.plusSeconds(299);
        session.completeMfa(promoted, 604800);
        assertThat(session.getId()).isEqualTo(original);
        assertThat(session.getAuthState()).isEqualTo(SessionAuthState.AUTHENTICATED);
        assertThat(session.getAbsoluteExpiresAt()).isEqualTo(created.plusSeconds(604800));
        assertThat(session.getLastActivityAt()).isEqualTo(promoted).isBefore(session.getAbsoluteExpiresAt());
        assertThat(session.idleExpiresAt()).isEqualTo(promoted.plusSeconds(900));
        assertThat(session.isUsable(promoted.plusSeconds(899))).isTrue();
        assertThat(session.isUsable(promoted.plusSeconds(900))).isFalse();
        assertThatThrownBy(() -> session.completeMfa(promoted, 604800)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void promotionRejectsExpiredRevokedAndNonAdminSessions() {
        Instant created = Instant.parse("2026-10-17T10:00:00Z");
        UserSession expired = new UserSession(new StaffUser("admin@example.com", HASH, StaffRole.ADMIN), created, 604800, null);
        Instant expiredAt = created.plusSeconds(300);
        Instant afterCreation = created.plusSeconds(1);
        assertThatThrownBy(() -> expired.completeMfa(expiredAt, 604800)).isInstanceOf(IllegalStateException.class);
        expired.revoke(created);
        assertThatThrownBy(() -> expired.completeMfa(afterCreation, 604800)).isInstanceOf(IllegalStateException.class);
        for (StaffRole role : new StaffRole[]{StaffRole.WAITER, StaffRole.KITCHEN}) {
            UserSession session = new UserSession(new StaffUser("staff@example.com", HASH, role), created, 604800, null);
            assertThatThrownBy(() -> session.completeMfa(afterCreation, 604800)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void enrollmentAndSharedFailureWindowHaveBoundedLifecycle() {
        Instant now = Instant.parse("2026-10-17T10:00:00Z");
        StaffMfa mfaState = new StaffMfa(UUID.randomUUID(), now);
        UUID sessionId = UUID.randomUUID();
        byte[] encrypted = new byte[48];
        mfaState.enroll(encrypted, sessionId, now, now.plusSeconds(300));
        encrypted[0] = 1;
        assertThat(mfaState.getEncryptedTotpSecret()[0]).isZero();
        assertThat(mfaState.enabled()).isFalse();
        for (int index = 0; index < 5; index++) { mfaState.failed(now.plusSeconds(index)); }
        assertThat(mfaState.getFailedAttempts()).isEqualTo(5);
        assertThat(mfaState.getLockedUntil()).isEqualTo(now.plusSeconds(304));
        mfaState.failed(now.plusSeconds(305));
        assertThat(mfaState.getFailedAttempts()).isEqualTo(1);
        assertThat(mfaState.getLockedUntil()).isNull();
        mfaState.enable(123, now.plusSeconds(306));
        assertThat(mfaState.enabled()).isTrue();
        assertThat(mfaState.getPendingSessionId()).isNull();
        assertThat(mfaState.getPendingExpiresAt()).isNull();
        mfaState.accepted(124, now.plusSeconds(307));
        assertThat(mfaState.getLastAcceptedCounter()).isEqualTo(124);
        mfaState.clearFailures(now.plusSeconds(308));
        assertThat(mfaState.getFailedAttempts()).isZero();
        assertThat(mfaState.getLockedUntil()).isNull();
    }

    @Test
    void peerLimitExpiresAndFloodingNeverRejectsNewPeers() {
        MutableAuthClock clock = new MutableAuthClock();
        MfaPeerLimiter limiter = new MfaPeerLimiter(clock);
        for (int index = 0; index < MfaPeerLimiter.ATTEMPTS_PER_WINDOW; index++) { limiter.check("127.0.0.1"); }
        assertThatThrownBy(() -> limiter.check("127.0.0.1")).isInstanceOf(LoginLimitException.class);
        clock.advance(MfaPeerLimiter.WINDOW_SECONDS);
        limiter.check("127.0.0.1");
        limiter.check(null);
        limiter.check("x".repeat(BoundedWindows.MAX_PEER_LENGTH + 1));
        for (int index = 0; index < BoundedWindows.MAX_TRACKED_KEYS; index++) { limiter.check("test-peer-" + index); }
        assertThatCode(() -> limiter.check("honest-peer")).doesNotThrowAnyException();
    }
}

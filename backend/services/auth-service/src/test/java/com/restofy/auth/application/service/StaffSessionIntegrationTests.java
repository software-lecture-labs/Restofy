package com.restofy.auth.application.service;

import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.restofy.auth.domain.entity.StaffUser;
import com.restofy.auth.domain.enums.SessionAuthState;
import com.restofy.auth.domain.enums.StaffRole;
import com.restofy.auth.infrastructure.persistence.repository.RefreshTokenRepository;
import com.restofy.auth.infrastructure.persistence.repository.StaffUserRepository;
import com.restofy.auth.infrastructure.persistence.repository.UserSessionRepository;
import com.restofy.auth.infrastructure.security.StaffJwtTokens;
import com.restofy.auth.infrastructure.security.StaffPasswords;
import com.restofy.auth.support.AuthSessionTestSupport;
import com.restofy.auth.support.AuthTestDatabaseInitializer;
import com.restofy.auth.support.MutableAuthClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"auth.bootstrap.dev-users.enabled=false", "auth.bootstrap.initial-admin.enabled=false"})
@ContextConfiguration(initializers = AuthTestDatabaseInitializer.class)
@Import(StaffSessionIntegrationTests.TimeConfiguration.class)
class StaffSessionIntegrationTests extends AuthSessionTestSupport {
    private static final String PASSWORD = "Session-test-password-01";
    @Autowired private StaffSessionService service;
    @Autowired private StaffUserRepository users;
    @Autowired private UserSessionRepository sessions;
    @Autowired private RefreshTokenRepository tokens;
    @Autowired private StaffPasswords passwords;
    @Autowired private StaffJwtTokens jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableAuthClock clock;
    private StaffUser waiter;

    @BeforeEach
    void setup() {
        tokens.deleteAll();
        sessions.deleteAll();
        users.deleteAll();
        clock.reset();
        waiter = users.saveAndFlush(new StaffUser("waiter@example.com", passwords.hash(PASSWORD), StaffRole.WAITER));
    }

    @Test
    void rotatesOnlyOnceAndCommitsReuseRevocationDespiteUnauthorizedResponse() {
        var first = login();
        var second = service.refresh(first.refresh());
        assertThat(second.refresh()).isNotEqualTo(first.refresh());
        assertThat(second.session().getId()).isEqualTo(first.session().getId());
        assertThat(second.session().getLastActivityAt()).isEqualTo(first.session().getLastActivityAt());
        assertThat(second.session().getAbsoluteExpiresAt()).isEqualTo(first.session().getAbsoluteExpiresAt());
        assertThat(tokens.findByTokenHash(StaffSessionService.digest(first.refresh())).orElseThrow().getUsedAt()).isNotNull();
        clock.advance(StaffSessionService.REFRESH_REUSE_GRACE_SECONDS);
        assertRefreshRejected(first);
        assertThat(sessions.findById(first.session().getId()).orElseThrow().getRevokedAt()).isNotNull();
        assertRefreshRejected(second);
        assertAccessRejected(second);
        assertThat(tokens.findAll()).allMatch(token -> token.getRevokedAt() != null);
    }

    @Test
    void twoDevicesHaveSeparateSessionsAndLogoutIsIdempotent() {
        var first = login();
        var other = login();
        service.logout(first.refresh(), first.access(), null);
        service.logout(first.refresh(), first.access(), null);
        service.logout(null, "invalid", null);
        service.logout("unknown", null, null);
        assertRefreshRejected(first);
        assertThat(service.refresh(other.refresh()).session().isUsable(clock.instant())).isTrue();
        assertThat(first.toString()).doesNotContain(first.access(), first.refresh());
        assertThat(tokens.findAll()).noneMatch(token -> token.getTokenHash().equals(first.refresh()));
    }

    @Test
    void logoutCanUseAccessWithoutRefresh() {
        var grant = login();
        service.logout(null, grant.access(), null);
        assertThat(sessions.findById(grant.session().getId()).orElseThrow().getRevokedAt()).isNotNull();
    }

    @Test
    void inactiveAccountRevokesEverySessionWhenRefreshedOrAuthenticated() {
        var first = login();
        var second = login();
        var third = login();
        var identity = jwt.verify(third.access(), false);
        String firstRefresh = first.refresh();
        String secondRefresh = second.refresh();
        jdbc.update("UPDATE staff_users SET is_active = false");
        assertThatThrownBy(() -> service.refresh(firstRefresh)).isInstanceOf(BadCredentialsException.class);
        assertThat(sessions.findAll()).allMatch(session -> session.getRevokedAt() != null);
        assertThat(tokens.findAll()).allMatch(token -> token.getRevokedAt() != null);
        jdbc.update("UPDATE user_sessions SET revoked_at = NULL");
        jdbc.update("UPDATE refresh_tokens SET revoked_at = NULL");
        assertThatThrownBy(() -> service.authenticate(identity)).isInstanceOf(BadCredentialsException.class);
        assertThat(sessions.findAll()).allMatch(session -> session.getRevokedAt() != null);
        assertThat(tokens.findAll()).allMatch(token -> token.getRevokedAt() != null);
        assertThatThrownBy(() -> service.refresh(secondRefresh)).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void logoutOnlyAcceptsTheTokenKindThatMatchesTheSessionState() {
        StaffUser admin = users.saveAndFlush(new StaffUser("admin-logout@example.com", passwords.hash(PASSWORD), StaffRole.ADMIN));
        var pending = service.login(admin.getEmailNormalized(), PASSWORD, null);
        service.logout(null, pending.access(), null);
        assertThat(sessions.findById(pending.session().getId()).orElseThrow().getRevokedAt()).isNull();
        service.logout(null, null, pending.access());
        assertThat(sessions.findById(pending.session().getId()).orElseThrow().getRevokedAt()).isNotNull();
        var other = login();
        service.logout(null, null, other.access());
        assertThat(sessions.findById(other.session().getId()).orElseThrow().getRevokedAt()).isNull();
    }

    @ParameterizedTest
    @EnumSource(StaffRole.class)
    void enforcesRoleSpecificIdleBoundaryWithoutRefreshActivity(StaffRole role) {
        StaffUser user = users.saveAndFlush(new StaffUser(role.name().toLowerCase(Locale.ROOT) + "@example.org", passwords
                .hash(PASSWORD), role));
        var grant = service.login(user.getEmailNormalized(), PASSWORD, null);
        if (role == StaffRole.ADMIN) {
            assertThat(grant.refresh()).isNull();
            assertThat(grant.session().getAuthState()).isEqualTo(SessionAuthState.MFA_PENDING);
            assertThat(grant.session().idleExpiresAt()).isEqualTo(clock.instant().plusSeconds(900));
            assertActivityRejected(grant, true, AccessDeniedException.class);
            clock.advance(300);
            String pendingAccess = grant.access();
            assertThatThrownBy(() -> jwt.verify(pendingAccess, true)).isInstanceOf(BadCredentialsException.class);
            return;
        }
        long boundary = role == StaffRole.KITCHEN ? 3600 : 900;
        clock.advance(boundary - 1);
        var rotated = service.refresh(grant.refresh());
        assertThat(rotated.session().getLastActivityAt()).isEqualTo(grant.session().getLastActivityAt());
        clock.advance(1);
        assertRefreshRejected(rotated);
        assertThat(sessions.findById(grant.session().getId()).orElseThrow().getRevokedAt()).isNotNull();
    }

    @Test
    void explicitActivityExtendsIdleButInspectionDoesNot() {
        var grant = login();
        var identity = jwt.verify(grant.access(), false);
        clock.advance(899);
        assertThat(service.authenticate(identity).getLastActivityAt()).isEqualTo(grant.session().getLastActivityAt());
        assertThat(service.activity(identity).getLastActivityAt()).isEqualTo(clock.instant());
        clock.advance(899);
        var rotated = service.refresh(grant.refresh());
        clock.advance(1);
        assertActivityRejected(rotated, false, BadCredentialsException.class);
    }

    @Test
    void cannotReviveExpiredOrCrossOwnedSession() {
        var grant = login();
        var identity = jwt.verify(grant.access(), false);
        StaffUser other = users.saveAndFlush(new StaffUser("other@example.com", passwords.hash(PASSWORD), StaffRole.WAITER));
        var otherIdentity = new StaffJwtTokens.Identity(other.getId(), identity.sessionId(), identity.role(), identity.state());
        var unknownUser = new StaffJwtTokens.Identity(UUID.randomUUID(), identity.sessionId(), identity.role(), identity.state());
        var unknownSession = new StaffJwtTokens.Identity(identity.userId(), UUID.randomUUID(), identity.role(), identity.state());
        assertThatThrownBy(() -> service.authenticate(otherIdentity)).isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> service.authenticate(unknownUser)).isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> service.authenticate(unknownSession)).isInstanceOf(BadCredentialsException.class);
        clock.advance(900);
        assertThatThrownBy(() -> service.activity(identity)).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void disablesAllSessionsAndRejectsNewLoginAndRefresh() {
        var first = login();
        var second = login();
        service.deactivate(waiter.getId());
        assertThat(users.findById(waiter.getId()).orElseThrow().isActive()).isFalse();
        assertThat(sessions.findAll()).allMatch(session -> session.getRevokedAt() != null);
        assertThat(tokens.findAll()).allMatch(token -> token.getRevokedAt() != null);
        assertThatThrownBy(this::login).isInstanceOf(BadCredentialsException.class);
        assertRefreshRejected(first);
        assertAccessRejected(second);
    }

    @Test
    void detectsDirectAccountDisableAndRevokesEverySession() {
        var grant = login();
        login();
        waiter.deactivate();
        users.saveAndFlush(waiter);
        assertRefreshRejected(grant);
        assertThat(sessions.findAll()).allMatch(session -> session.getRevokedAt() != null);
    }

    @Test
    void deniesDisabledAccountDuringAccessValidation() {
        var grant = login();
        waiter.deactivate();
        users.saveAndFlush(waiter);
        assertAccessRejected(grant);
    }

    @Test
    void enforcesAbsoluteDeadlineDespiteRecentActivity() {
        var grant = login();
        jdbc.update("UPDATE user_sessions SET absolute_expires_at = ? WHERE id = ?",
                Timestamp.from(clock.instant().plusSeconds(100)), grant.session().getId());
        clock.advance(99);
        service.activity(jwt.verify(grant.access(), false));
        var rotated = service.refresh(grant.refresh());
        assertThat(rotated.session().getAbsoluteExpiresAt()).isEqualTo(clock.instant().plusSeconds(1));
        clock.advance(1);
        assertRefreshRejected(rotated);
    }

    @Test
    void databaseRejectsInvalidHashGenerationAndDuplicateLiveRefresh() {
        var grant = login();
        String sql = "INSERT INTO refresh_tokens(id,session_id,token_hash,"
                + "created_at,expires_at,generation) VALUES (?,?,?,now(),now()+interval '1 day',?)";
        UUID rowId = UUID.randomUUID();
        UUID sessionId = grant.session().getId();
        UUID unknownSessionId = UUID.randomUUID();
        String hashA = "a".repeat(64);
        String hashB = "b".repeat(64);
        assertThatThrownBy(() -> jdbc.update(sql, rowId, sessionId, "not-hash", 1)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(sql, rowId, sessionId, hashA, -1)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(sql, rowId, sessionId, hashA, 1)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(sql, rowId, unknownSessionId, hashB, 1)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE user_sessions SET last_activity_at=absolute_expires_at WHERE id=?", sessionId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void rejectsMissingInvalidAndUnknownRefresh() {
        for (String raw : new String[]{null, "invalid", "a".repeat(43)}) {
            assertThatThrownBy(() -> service.refresh(raw)).isInstanceOf(BadCredentialsException.class);
        }
        assertThatThrownBy(() -> service.login("waiter@example.com", "incorrect", null)).isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> service.login("missing@example.com", PASSWORD, null)).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void simultaneousRefreshFromTwoTabsKeepsTheSessionAndIssuesCredentialsOnlyOnce() throws Exception {
        var grant = login();
        assertThat(race(() -> refreshIssuedCredentials(grant.refresh()), () -> refreshIssuedCredentials(grant.refresh())))
                .containsExactlyInAnyOrder(true, false);
        assertThat(tokens.count()).isEqualTo(2);
        assertThat(sessions.findById(grant.session().getId()).orElseThrow().getRevokedAt()).isNull();
        assertThat(tokens.findAll()).noneMatch(token -> token.getRevokedAt() != null);
    }

    @Test
    void replayInsideTheGraceWindowGetsNoCredentialsAndNeverExtendsTheWindow() {
        var first = login();
        var winner = service.refresh(first.refresh());
        clock.advance(StaffSessionService.REFRESH_REUSE_GRACE_SECONDS - 1);
        var replay = service.refresh(first.refresh());
        assertThat(replay.concurrentReplay()).isTrue();
        assertThat(replay.access()).isNull();
        assertThat(replay.refresh()).isNull();
        assertThat(replay.session().getId()).isEqualTo(first.session().getId());
        assertThat(tokens.count()).isEqualTo(2);
        clock.advance(1);
        assertRefreshRejected(first);
        assertRefreshRejected(winner);
        assertThat(sessions.findById(first.session().getId()).orElseThrow().getRevokedAt()).isNotNull();
    }

    @Test
    void replayAfterTheGraceWindowRevokesEverySiblingToken() {
        var first = login();
        var winner = service.refresh(first.refresh());
        clock.advance(StaffSessionService.REFRESH_REUSE_GRACE_SECONDS);
        assertRefreshRejected(first);
        assertRefreshRejected(winner);
        assertThat(tokens.findAll()).allMatch(token -> token.getRevokedAt() != null);
    }

    @Test
    void refreshCannotSurviveConcurrentDisable() throws Exception {
        var grant = login();
        race(() -> refreshOutcome(grant.refresh()), () -> { service.deactivate(waiter.getId());
        return true;
        });
        assertThat(sessions.findAll()).allMatch(session -> session.getRevokedAt() != null);
        assertRefreshRejected(grant);
    }

    @Test
    void loginCannotSurviveConcurrentDisable() throws Exception {
        race(() -> { try { login();
        return true;
        } catch (BadCredentialsException _) {
            return false;
        } },
                () -> { service.deactivate(waiter.getId());
                return true;
                });
        assertThat(sessions.findAll()).allMatch(session -> session.getRevokedAt() != null);
        assertThatThrownBy(this::login).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void pendingAdminCanLogoutWithoutReceivingRefresh() {
        users.saveAndFlush(new StaffUser("admin@example.com", passwords.hash(PASSWORD), StaffRole.ADMIN));
        var grant = service.login("admin@example.com", PASSWORD, null);
        String pendingAccess = grant.access();
        assertThatThrownBy(() -> jwt.verify(pendingAccess, false)).isInstanceOf(BadCredentialsException.class);
        service.logout(null, null, grant.access());
        assertThat(sessions.findById(grant.session().getId()).orElseThrow().getRevokedAt()).isNotNull();
        assertThat(tokens.count()).isZero();
    }

    private void assertRefreshRejected(StaffSessionService.Grant grant) {
        String raw = grant.refresh();
        assertThatThrownBy(() -> service.refresh(raw)).isInstanceOf(BadCredentialsException.class);
    }

    private void assertAccessRejected(StaffSessionService.Grant grant) {
        var identity = jwt.verify(grant.access(), false);
        assertThatThrownBy(() -> service.authenticate(identity)).isInstanceOf(BadCredentialsException.class);
    }

    private void assertActivityRejected(StaffSessionService.Grant grant, boolean pending, Class<? extends Throwable> expected) {
        var identity = jwt.verify(grant.access(), pending);
        assertThatThrownBy(() -> service.activity(identity)).isInstanceOf(expected);
    }

    private StaffSessionService.Grant login() {

        return service.login("waiter@example.com", PASSWORD, "Test browser");
    }
    private boolean refreshIssuedCredentials(String raw) {
        return !service.refresh(raw).concurrentReplay();
    }
    private boolean refreshOutcome(String raw) {
        try { service.refresh(raw);
        return true;
        } catch (BadCredentialsException _) {
            return false;
        }
    }
    private List<Boolean> race(Callable<Boolean> first, Callable<Boolean> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var left = executor.submit(() -> { start.await();
            return first.call();
            });
            var right = executor.submit(() -> { start.await();
            return second.call();
            });
            start.countDown();
            return List.of(left.get(30, TimeUnit.SECONDS), right.get(30, TimeUnit.SECONDS));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean @Primary MutableAuthClock testClock() {
            return new MutableAuthClock();
        }
    }
}

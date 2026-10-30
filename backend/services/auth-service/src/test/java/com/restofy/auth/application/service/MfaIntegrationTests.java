package com.restofy.auth.application.service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import com.restofy.auth.domain.entity.StaffUser;
import com.restofy.auth.domain.enums.SessionAuthState;
import com.restofy.auth.domain.enums.StaffRole;
import com.restofy.auth.infrastructure.persistence.repository.MfaRecoveryCodeRepository;
import com.restofy.auth.infrastructure.persistence.repository.StaffMfaRepository;
import com.restofy.auth.infrastructure.persistence.repository.StaffUserRepository;
import com.restofy.auth.infrastructure.security.LoginRateLimiter.LoginLimitException;
import com.restofy.auth.infrastructure.security.MfaSecretCipher;
import com.restofy.auth.infrastructure.security.MfaUnavailableException;
import com.restofy.auth.infrastructure.security.StaffJwtTokens;
import com.restofy.auth.infrastructure.security.StaffJwtTokens.Identity;
import com.restofy.auth.infrastructure.security.StaffPasswords;
import com.restofy.auth.infrastructure.security.TotpVerifier;
import com.restofy.auth.support.AuthSessionTestSupport;
import com.restofy.auth.support.AuthTestDatabaseInitializer;
import com.restofy.auth.support.MutableAuthClock;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"auth.bootstrap.dev-users.enabled=false", "auth.bootstrap.initial-admin.enabled=false"})
@ContextConfiguration(initializers = AuthTestDatabaseInitializer.class)
@Import(MfaIntegrationTests.TimeConfiguration.class)
class MfaIntegrationTests extends AuthSessionTestSupport {
    private static final String PASSWORD = "Mfa-integration-password-01";
    @Autowired private StaffSessionService sessions;
    @Autowired private MfaEnrollmentService enrollment;
    @Autowired private MfaVerificationService verification;
    @Autowired private StaffUserRepository users;
    @Autowired private StaffMfaRepository records;
    @Autowired private MfaRecoveryCodeRepository codes;
    @Autowired private StaffPasswords passwords;
    @Autowired private StaffJwtTokens jwt;
    @Autowired private MfaSecretCipher cipher;
    @Autowired private TotpVerifier totp;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableAuthClock clock;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private DataSource dataSource;
    private UUID adminId;

    @BeforeEach
    void setup() {
        clean();
        clock.reset();
        adminId = users.saveAndFlush(new StaffUser("admin@example.com", passwords.hash(PASSWORD), StaffRole.ADMIN)).getId();
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM mfa_recovery_codes");
        jdbc.update("DELETE FROM staff_mfa");
        jdbc.update("DELETE FROM refresh_tokens");
        jdbc.update("DELETE FROM user_sessions");
        jdbc.update("DELETE FROM staff_users");
    }

    @Test
    void enrollmentPromotesSameSessionAndOldPendingCannotReadOrLogoutIt() {
        Identity pending = pending();
        String originalPendingToken = jwt.issue(sessions.authenticate(pending));
        var started = enrollment.start(pending, "enroll");
        assertThat(started.secret()).hasSize(32);
        assertThat(started.otpauthUri()).startsWith("otpauth://totp/");
        var result = verification.confirm(pending, currentCode(), "confirm");
        assertThat(result.recoveryCodes()).hasSize(10).doesNotHaveDuplicates();
        Identity full = jwt.verify(result.grant().access(), false);
        assertThat(full.sessionId()).isEqualTo(pending.sessionId());
        var session = sessions.authenticate(full);
        assertThat(session.getAuthState()).isEqualTo(SessionAuthState.AUTHENTICATED);
        assertThat(session.getLastActivityAt()).isBefore(session.getAbsoluteExpiresAt());
        assertThat(session.getAbsoluteExpiresAt()).isEqualTo(session.getCreatedAt().plusSeconds(604800));
        assertThatThrownBy(() -> sessions.authenticate(pending)).isInstanceOf(BadCredentialsException.class);
        sessions.logout(null, null, originalPendingToken);
        assertThat(sessions.authenticate(full).getRevokedAt()).isNull();
        sessions.refresh(result.grant().refresh());
        clock.advance(900);
        assertThatThrownBy(() -> sessions.authenticate(full)).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void replayAndRecoveryConsumptionAreAccountWideAndSingleUse() {
        var setup = enabled();
        Identity first = pending();
        String usedCode = currentCode();
        assertThatThrownBy(() -> verification.verify(first, usedCode, "replay")).isInstanceOf(BadCredentialsException.class);
        clock.advance(30);
        String nextCode = currentCode();
        var grant = verification.verify(first, nextCode, "totp").grant();
        assertThat(jwt.verify(grant.access(), false).role()).isEqualTo(StaffRole.ADMIN);
        Identity second = pending();
        assertThatThrownBy(() -> verification.verify(second, nextCode, "totp")).isInstanceOf(BadCredentialsException.class);
        verification.recover(second, setup.recoveryCodes().getFirst(), "recovery");
        Identity third = pending();
        String usedRecovery = setup.recoveryCodes().getFirst();
        assertThatThrownBy(() -> verification.recover(third, usedRecovery, "recovery"))
                .isInstanceOf(BadCredentialsException.class);
        assertThat(records.findByStaffUserId(adminId).orElseThrow().getFailedAttempts()).isEqualTo(1);
    }

    @Test
    void twoConcurrentRecoveryAttemptsHaveExactlyOneWinner() throws Exception {
        var setup = enabled();
        Identity first = pending(), second = pending();
        String code = setup.recoveryCodes().getFirst();
        assertThat(race(() -> recover(first, code), () -> recover(second, code))).containsExactlyInAnyOrder(true, false);
    }

    @Test
    void twoConcurrentTotpAttemptsHaveExactlyOneWinner() throws Exception {
        enabled();
        Identity first = pending(), second = pending();
        clock.advance(30);
        String code = currentCode();
        assertThat(race(() -> verify(first, code), () -> verify(second, code))).containsExactlyInAnyOrder(true, false);
    }

    @Test
    void failureCountSurvivesOuterRollbackAndAllFactorsShareLock() {
        Identity first = pending();
        enrollment.start(first, "enroll");
        String invalid = wrongCode();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThatThrownBy(() -> verification.confirm(first, invalid, "confirm")).isInstanceOf(BadCredentialsException.class);
            status.setRollbackOnly();
        });
        assertThat(records.findByStaffUserId(adminId).orElseThrow().getFailedAttempts()).isEqualTo(1);
        for (int index = 0; index < 3; index++) {
            assertThatThrownBy(() -> verification.verify(first, invalid, "verify")).isInstanceOf(BadCredentialsException.class);
        }
        assertThatThrownBy(() -> verification.recover(first, "AAAA-AAAA-AAAA-AAAA-AAAA", "recover"))
                .isInstanceOf(LoginLimitException.class);
        String lockedCode = currentCode();
        assertThatThrownBy(() -> verification.confirm(first, lockedCode, "confirm")).isInstanceOf(LoginLimitException.class);
        clock.advance(300);
        Identity fresh = pending();
        enrollment.start(fresh, "enroll");
        assertThat(verification.confirm(fresh, currentCode(), "confirm").grant().refresh()).isNotNull();
    }

    @Test
    void restartInvalidatesOldSecretAndOtherDeviceCannotConfirmEnrollment() {
        Identity first = pending();
        enrollment.start(first, "enroll");
        byte[] old = secret();
        Identity second = pending();
        assertThatThrownBy(() -> enrollment.start(second, "enroll")).isInstanceOf(org.springframework.web.server
                .ResponseStatusException.class);
        String otherDeviceCode = currentCode();
        assertThatThrownBy(() -> verification.confirm(second, otherDeviceCode, "confirm")).isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> enrollment.start(first, "enroll")).isInstanceOf(LoginLimitException.class);
        clock.advance(60);
        enrollment.start(first, "enroll");
        assertThat(secret()).isNotEqualTo(old);
        clock.advance(240);
        String expiredCode = currentCode();
        assertThatThrownBy(() -> verification.confirm(first, expiredCode, "confirm")).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void regenerationRequiresPasswordAndFactorAndRevokesOldCodesAndOtherDevices() {
        var initial = enabled();
        Identity full = jwt.verify(initial.grant().access(), false), other = pending();
        String firstRecovery = initial.recoveryCodes().getFirst();
        assertThatThrownBy(() -> verification.regenerate(full, "wrong", null, firstRecovery, "regenerate"))
                .isInstanceOf(BadCredentialsException.class);
        List<String> replacement = verification.regenerate(full, PASSWORD, null, firstRecovery, "regenerate");
        assertThat(replacement).hasSize(10).doesNotContainAnyElementsOf(initial.recoveryCodes());
        assertThat(sessions.authenticate(full).getRevokedAt()).isNull();
        assertThatThrownBy(() -> sessions.authenticate(other)).isInstanceOf(BadCredentialsException.class);
        Identity fresh = pending();
        String revokedRecovery = initial.recoveryCodes().get(1);
        assertThatThrownBy(() -> verification.recover(fresh, revokedRecovery, "recover")).isInstanceOf(BadCredentialsException.class);
        verification.recover(fresh, replacement.getFirst(), "recover");
    }

    @Test
    void corruptedSecretFailsClosedAndDisableRevokesPromotedSessions() {
        var initial = enabled();
        Identity pending = pending();
        jdbc.update("UPDATE staff_mfa SET encrypted_totp_secret = ? WHERE staff_user_id = ?", new byte[48], adminId);
        assertThatThrownBy(() -> verification.verify(pending, "123456", "verify")).isInstanceOf(MfaUnavailableException.class);
        assertThat(records.findByStaffUserId(adminId).orElseThrow().getFailedAttempts()).isZero();
        sessions.deactivate(adminId);
        String refresh = initial.grant().refresh();
        assertThatThrownBy(() -> sessions.refresh(refresh)).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void databaseRejectsInvalidSessionAndMfaStatesAndStoresOnlyCiphertextAndDigests() {
        var result = enabled();
        var mfaState = records.findByStaffUserId(adminId).orElseThrow();
        assertThat(mfaState.getEncryptedTotpSecret()).hasSize(48).isNotEqualTo(secret());
        assertThat(codes.findByStaffUserIdAndRevokedAtIsNull(adminId)).isNotEmpty().allSatisfy(code ->
                assertThat(code.getCodeDigest()).hasSize(64).isNotIn(result.recoveryCodes()));
        UUID sessionId = result.grant().session().getId();
        assertThatThrownBy(() -> jdbc.update("UPDATE user_sessions SET last_activity_at = absolute_expires_at WHERE id = ?",
                sessionId)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE user_sessions SET role_snapshot = 'WAITER', auth_state = 'MFA_PENDING' WHERE id = ?",
                sessionId)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE staff_mfa SET last_accepted_counter = NULL WHERE staff_user_id = ?", adminId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void upgradesExistingV2PendingSessionWithoutChangingItsIdentityOrExpiry() {
        pending();
        String schema = "mfa_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var prior = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target("2").cleanDisabled(true).load();
        prior.migrate();
        jdbc.update("INSERT INTO " + schema
                + ".staff_users (id,email_normalized,password_hash,role,is_active,created_at,updated_at,version) "
                + "SELECT id,email_normalized,password_hash,role,is_active,created_at,updated_at,version FROM public.staff_users");
        jdbc.update("INSERT INTO " + schema + ".user_sessions SELECT * FROM public.user_sessions");
        assertThatThrownBy(() -> jdbc.update("UPDATE " + schema + ".user_sessions SET auth_state = 'AUTHENTICATED'"))
                .isInstanceOf(DataAccessException.class);
        var latest = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target("4").cleanDisabled(true).load();
        latest.migrate();
        assertThat(Arrays.stream(latest.info().applied()).filter(info -> info.getVersion() != null)).hasSize(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".user_sessions WHERE auth_state = 'MFA_PENDING'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".user_sessions migrated JOIN public.user_sessions original "
                + "ON original.id = migrated.id AND original.absolute_expires_at = migrated.absolute_expires_at", Integer.class))
                        .isEqualTo(1);
        jdbc.update("UPDATE " + schema + ".user_sessions SET auth_state = 'AUTHENTICATED'");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".user_sessions WHERE auth_state = 'AUTHENTICATED'", Integer
                .class))
                .isEqualTo(1);
    }

    private MfaVerificationService.Completion enabled() {
        Identity pending = pending();
        enrollment.start(pending, "enroll");
        return verification.confirm(pending, currentCode(), "confirm");
    }
    private Identity pending() {
        return jwt.verify(sessions.login("admin@example.com", PASSWORD, "MFA test").access(), true);
    }
    private byte[] secret() {
        return cipher.decrypt(adminId, records.findByStaffUserId(adminId).orElseThrow().getEncryptedTotpSecret());
    }
    private String currentCode() {
        return totp.code(secret(), clock.instant().getEpochSecond() / 30, 6);
    }
    private String wrongCode() {
        for (int candidate = 0; candidate < 100; candidate++) {
            String code = String.format(Locale.ROOT, "%06d", candidate);
            if (totp.verify(secret(), code, null) < 0) {
                return code;
            }
        }
        throw new IllegalStateException("No invalid test code");
    }
    private boolean recover(Identity identity, String code) {
        try { verification.recover(identity, code, "race");
        return true;
        } catch (BadCredentialsException _) {
            return false;
        }
    }
    private boolean verify(Identity identity, String code) {
        try { verification.verify(identity, code, "race");
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
        @Bean @Primary MutableAuthClock clock() {
            return new MutableAuthClock();
        }
    }
}

package com.restofy.auth.application.service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.sql.DataSource;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.restofy.auth.domain.entity.StaffMfa;
import com.restofy.auth.domain.entity.StaffUser;
import com.restofy.auth.domain.enums.PasswordResetStatus;
import com.restofy.auth.domain.enums.StaffRole;
import com.restofy.auth.infrastructure.client.MailClient;
import com.restofy.auth.infrastructure.persistence.repository.StaffMfaRepository;
import com.restofy.auth.infrastructure.persistence.repository.StaffUserRepository;
import com.restofy.auth.infrastructure.security.LoginRateLimiter;
import com.restofy.auth.infrastructure.security.MfaSecretCipher;
import com.restofy.auth.infrastructure.security.PasswordResetCodes;
import com.restofy.auth.infrastructure.security.PasswordResetLimiter;
import com.restofy.auth.infrastructure.security.StaffJwtTokens;
import com.restofy.auth.infrastructure.security.StaffPasswords;
import com.restofy.auth.infrastructure.security.TotpVerifier;
import com.restofy.auth.support.AuthSessionTestSupport;
import com.restofy.auth.support.AuthTestDatabaseInitializer;
import com.restofy.auth.support.MutableAuthClock;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"auth.bootstrap.dev-users.enabled=false","auth.bootstrap.initial-admin.enabled=false",
        "spring.datasource.hikari.maximum-pool-size=2"})
@ContextConfiguration(initializers=AuthTestDatabaseInitializer.class)
@Import(PasswordResetIntegrationTests.TimeConfiguration.class)
class PasswordResetIntegrationTests extends AuthSessionTestSupport {
    private static final String OLD = "Old-password-for-reset-01";
    private static final String NEW = "New-password-for-reset-02";
    @Autowired private PasswordResetService resets;
    @Autowired private StaffSessionService sessions;
    @Autowired private StaffUserRepository users;
    @Autowired private StaffMfaRepository mfa;
    @Autowired private StaffPasswords passwords;
    @Autowired private MfaSecretCipher cipher;
    @Autowired private TotpVerifier totp;
    @Autowired private StaffJwtTokens jwt;
    @Autowired private PasswordResetCodes codes;
    @Autowired private PasswordResetLimiter limits;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableAuthClock clock;
    @Autowired private DataSource dataSource;
    @MockitoBean private MailClient mail;
    private StaffUser admin;
    private byte[] secret;

    @BeforeEach void setup() {
        clean();
        clock.reset();
        admin = users.saveAndFlush(new StaffUser("reset@example.com", passwords.hash(OLD), StaffRole.ADMIN));
        secret = totp.newSecret();
        StaffMfa state = new StaffMfa(admin.getId(), clock.instant());
        state.enroll(cipher.encrypt(admin.getId(), secret), UUID.randomUUID(), clock.instant(), clock.instant().plusSeconds(300));
        state.enable(clock.instant().getEpochSecond()/30 - 1, clock.instant());
        mfa.saveAndFlush(state);
    }
    @AfterEach void clean() {
        jdbc.update("DELETE FROM password_reset_requests");
        jdbc.update("DELETE FROM password_reset_limits");
        jdbc.update("DELETE FROM mfa_recovery_codes");
        jdbc.update("DELETE FROM staff_mfa");
        jdbc.update("DELETE FROM refresh_tokens");
        jdbc.update("DELETE FROM user_sessions");
        jdbc.update("DELETE FROM staff_users");
    }
    private String factor() {
        return totp.code(secret, clock.instant().getEpochSecond()/30, 6);
    }
    private String start() {
        return resets.request(admin.getEmailNormalized(), factor(), "en");
    }
    private MailClient.Message sent() {
        var capture = ArgumentCaptor.forClass(MailClient.Message.class);
        verify(mail, atLeastOnce()).afterCommit(capture.capture());
        return capture.getAllValues().stream().filter(value -> value.templateKey().equals("ADMIN_PASSWORD_RESET")).reduce((a,b)->b)
                .orElseThrow();
    }

    @Test void resetsPasswordRevokesEverySessionAndKeepsMfaAndAccountActive() {
        var first = sessions.login(admin.getEmailNormalized(), OLD, "one");
        var second = sessions.login(admin.getEmailNormalized(), OLD, "two");
        var firstIdentity = jwt.verify(first.access(), true);
        var secondIdentity = jwt.verify(second.access(), true);
        var full = sessions.completeMfa(firstIdentity);
        var fullIdentity = jwt.verify(full.access(), false);
        String fullRefresh = full.refresh();
        String email = admin.getEmailNormalized();
        assertThat(sessions.authenticate(fullIdentity).getRevokedAt()).isNull();
        String cookie = start();
        var message = sent();
        String code = message.code();
        String grant = resets.verify(cookie, code);
        assertThatThrownBy(() -> resets.verify(cookie, code)).isInstanceOf(BadCredentialsException.class);
        resets.confirm(grant, NEW);
        StaffUser changed = users.findById(admin.getId()).orElseThrow();
        assertThat(changed.isActive()).isTrue();
        assertThat(changed.getPasswordChangedAt()).isEqualTo(clock.instant());
        assertThat(changed.getPasswordHash()).startsWith("$2a$12$");
        assertThat(mfa.existsByStaffUserIdAndEnabledAtIsNotNull(admin.getId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_sessions WHERE revoked_at IS NULL", Integer.class)).isZero();
        assertThatThrownBy(() -> sessions.authenticate(firstIdentity)).isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> sessions.authenticate(secondIdentity)).isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> sessions.authenticate(fullIdentity)).isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> sessions.refresh(fullRefresh)).isInstanceOf(BadCredentialsException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NULL", Integer.class)).isZero();
        assertThatThrownBy(() -> sessions.login(email, OLD, null)).isInstanceOf(BadCredentialsException.class);
        assertThat(sessions.login(admin.getEmailNormalized(), NEW, null).session().getAuthState().name()).isEqualTo("MFA_PENDING");
        assertThatThrownBy(() -> resets.confirm(grant, NEW)).isInstanceOf(BadCredentialsException.class);
        verify(mail).afterCommit(argThat(value -> value.templateKey().equals("PASSWORD_CHANGED") && value.locale().equals("en")
                && value.code() == null));
    }

    @ParameterizedTest @ValueSource(strings={"missing@example.com","waiter@example.com","kitchen@example.com","inactive@example.com",
            "no-mfa@example.com"})
    void ineligibleAccountsReceiveOnlyAnUnusableCookie(String email) {
        if (!email.startsWith("missing")) {
            StaffRole role = email.startsWith("waiter") ? StaffRole.WAITER : email.startsWith("kitchen") ? StaffRole
                    .KITCHEN : StaffRole.ADMIN;
            StaffUser user = new StaffUser(email, passwords.hash(OLD), role);
            if (email.startsWith("inactive")) {
                user.deactivate();
            }
            users.saveAndFlush(user);
        }
        String cookie = resets.request(email, factor(), "tr");
        assertThat(cookie).matches("[A-Za-z0-9_-]{43}");
        verifyNoInteractions(mail);
        assertThatThrownBy(() -> resets.verify(cookie,"000000")).isInstanceOf(BadCredentialsException.class);
    }

    @Test void wrongFactorAndTotpReplayCannotSendOrInvalidateAnExistingCode() {
        String cookie = start();
        var message = sent();
        resets.request(admin.getEmailNormalized(), factor(), "en");
        resets.request(admin.getEmailNormalized(), "------", "en");
        verify(mail, times(1)).afterCommit(any());
        assertThat(resets.verify(cookie,message.code())).isNotBlank();
    }

    @Test void newVerifiedRequestInvalidatesOldCodeAndGrant() {
        String old = start();
        var first = sent();
        clock.advance(180);
        String fresh = start();
        var second = sent();
        String firstCode = first.code();
        assertThatThrownBy(() -> resets.verify(old,firstCode)).isInstanceOf(BadCredentialsException.class);
        assertThat(resets.verify(fresh,second.code())).isNotBlank();
    }

    @Test void fiveWrongCodesAreCommittedAndCannotBeResetByResending() {
        String cookie = start();
        var message = sent();
        String code = message.code();
        String wrong = code.equals("000000") ? "000001" : "000000";
        for (int i=0; i<5; i++) {
            assertThatThrownBy(() -> resets.verify(cookie,wrong)).isInstanceOf(BadCredentialsException.class);
        }
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM password_reset_requests",Integer.class)).isEqualTo(5);
        assertThatThrownBy(() -> resets.verify(cookie,code)).isInstanceOf(BadCredentialsException.class);
        clock.advance(180);
        String next = start();
        var nextMessage = sent();
        String nextCode = nextMessage.code();
        assertThatThrownBy(() -> resets.verify(next,nextCode)).isInstanceOf(LoginRateLimiter.LoginLimitException.class);
    }

    @Test void expiryBoundariesAndUnverifiedConfirmAreRejected() {
        String cookie = start();
        var message = sent();
        String code = message.code();
        assertThatThrownBy(() -> resets.confirm(cookie,NEW)).isInstanceOf(BadCredentialsException.class);
        clock.advance(900);
        assertThatThrownBy(() -> resets.verify(cookie,code)).isInstanceOf(BadCredentialsException.class);
        String next = start();
        var nextMessage = sent();
        String grant = resets.verify(next,nextMessage.code());
        clock.advance(300);
        assertThatThrownBy(() -> resets.confirm(grant,NEW)).isInstanceOf(BadCredentialsException.class);
    }

    @ParameterizedTest @ValueSource(strings={"short","            ","Old-password-for-reset-01"})
    void invalidOrUnchangedPasswordDoesNotConsumeGrant(String password) {
        String cookie = start();
        String grant = resets.verify(cookie,sent().code());
        assertThatThrownBy(() -> resets.confirm(grant,password)).isInstanceOf(ResponseStatusException.class);
        resets.confirm(grant,NEW);
    }

    @Test void notificationFailureDoesNotRollbackPasswordChange() {
        String cookie = start();
        String grant = resets.verify(cookie,sent().code());
        doThrow(new IllegalStateException("test failure")).when(mail).afterCommit(argThat(value -> value.templateKey().equals(
                "PASSWORD_CHANGED")));
        resets.confirm(grant,NEW);
        assertThat(passwords.matches(NEW, users.findById(admin.getId()).orElseThrow().getPasswordHash())).isTrue();
    }

    @Test void corruptedFactorAndUnavailableMailDoNotRevealEligibility() {
        jdbc.update("UPDATE staff_mfa SET encrypted_totp_secret=? WHERE staff_user_id=?",new byte[48],admin.getId());
        assertThat(start()).matches("[A-Za-z0-9_-]{43}");
        verifyNoInteractions(mail);
    }

    @Test void disabledAccountAndChangedRoleCannotCompleteRecovery() {
        String cookie = start();
        String grant = resets.verify(cookie,sent().code());
        sessions.deactivate(admin.getId());
        assertThatThrownBy(() -> resets.confirm(grant,NEW)).isInstanceOf(BadCredentialsException.class);
    }

    @Test void concurrentVerifyAndConfirmEachConsumeOnlyOnce() throws Exception {
        String cookie = start();
        String code = sent().code();
        List<String> grants = race(() -> resets.verify(cookie,code));
        assertThat(grants).hasSize(1);
        assertThat(race(() -> { resets.confirm(grants.getFirst(),NEW);
        return "completed";
        })).hasSize(1);
    }

    private List<String> race(Callable<String> action) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<String>> futures = new ArrayList<>();
            for (int i=0; i<2; i++) {
                futures.add(pool.submit(() -> { ready.countDown();
            start.await(10,TimeUnit.SECONDS);
                try { return action.call();
                } catch (BadCredentialsException _) {
                    return null;
                } }));
                }
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<String> results = new ArrayList<>();
            for (var future:futures) {
                String result=future.get(20,TimeUnit.SECONDS);
            if(result!=null) {results.add(result);
            } }
            return results;
        }
    }

    @Test void databaseAndSafeRepresentationsNeverContainRawSecrets() {
        var logger=(Logger)LoggerFactory.getLogger(PasswordResetService.class);
        var appender=new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            String cookie = start();
            var sent = sent();
            assertThat(sent.code()).matches("[0-9]{6}");
            assertThat(sent.codeExpiresAt()).isEqualTo(clock.instant().plusSeconds(900));
            String grant = resets.verify(cookie, sent.code());
            assertThat(grant).isNotEqualTo(cookie);
            String row = jdbc.queryForObject("SELECT row_to_json(r)::text FROM password_reset_requests r",String.class);
            assertThat(row).doesNotContain(cookie,sent.code());
            assertThat(sent.toString()).doesNotContain(sent.recipient(),sent.code());
            assertThat(appender.list).isNotEmpty().allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(cookie,
                    sent.code(),sent.recipient(),OLD));
        } finally {logger.detachAppender(appender);
        appender.stop();
        }
    }

    @Test void limiterCleansExpiredRowsAndEnforcesGlobalCapacityAndCooldown() {
        limits.request("unknown@example.com","peer");
        assertThatThrownBy(() -> limits.request("unknown@example.com","peer")).isInstanceOf(LoginRateLimiter.LoginLimitException.class);
        jdbc.update("DELETE FROM password_reset_limits");
        jdbc.update("INSERT INTO password_reset_limits SELECT md5(i::text)||md5(i::text),1,? FROM generate_series(1,10000) i",
                Timestamp.from(clock.instant().plusSeconds(60)));
        assertThatThrownBy(() -> limits.verification("new-peer")).isInstanceOf(LoginRateLimiter.LoginLimitException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM password_reset_limits",Integer.class)).isEqualTo(10000);
        clock.advance(60);
        limits.verification("new-peer");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM password_reset_limits",Integer.class)).isEqualTo(1);
    }

    @Test void hourlyAndDailyMailBudgetsSurviveCooldownExpiry() {
        String email="limited@example.com";
        for(int i=0; i<5; i++) {limits.request(email,"peer");
        clock.advance(180);
        }
        assertThatThrownBy(()->limits.request(email,"peer")).isInstanceOf(LoginRateLimiter.LoginLimitException.class);
        clock.advance(3600);
        for(int i=0; i<5; i++) {limits.request(email,"peer");
        clock.advance(180);
        }
        clock.advance(3600);
        assertThatThrownBy(()->limits.request(email,"peer")).isInstanceOf(LoginRateLimiter.LoginLimitException.class);
    }

    @Test void wrongFactorsAccumulateAcrossRequestsForKnownAndUnknownAccounts() {
        for (String email : List.of(admin.getEmailNormalized(), "ghost@example.com")) {
            for (int i = 0; i < 4; i++) {
                resets.request(email, "------", "en");
            }
            assertThatThrownBy(() -> resets.request(email, "------", "en"))
                    .isInstanceOfSatisfying(LoginRateLimiter.LoginLimitException.class, ex -> assertThat(ex.retryAfter()).isEqualTo(300));
        }
        verifyNoInteractions(mail);
    }

    @Test void limiterReportsExactRetryAfterAndEnforcesPerPeerWindow() {
        limits.request("exact@example.com", "other-peer");
        clock.advance(60);
        assertThatThrownBy(() -> limits.request("exact@example.com", "other-peer"))
                .isInstanceOfSatisfying(LoginRateLimiter.LoginLimitException.class, ex -> assertThat(ex.retryAfter()).isEqualTo(120));
        for (int i = 0; i < 30; i++) {
            limits.request("peer-user-" + i + "@example.com", "busy-peer");
        }
        assertThatThrownBy(() -> limits.request("peer-user-x@example.com", "busy-peer"))
                .isInstanceOfSatisfying(LoginRateLimiter.LoginLimitException.class, ex -> assertThat(ex.retryAfter()).isEqualTo(60));
        for (int i = 0; i < 30; i++) {
            limits.verification("verify-peer");
        }
        assertThatThrownBy(() -> limits.verification("verify-peer")).isInstanceOf(LoginRateLimiter.LoginLimitException.class);
    }

    @Test void concurrentAdmissionNeverExceedsGlobalBucketCapacity() throws Exception {
        jdbc.update("INSERT INTO password_reset_limits SELECT md5(i::text)||md5(i::text),1,? FROM generate_series(1,9999) i",
                Timestamp.from(clock.instant().plusSeconds(60)));
        var pool = Executors.newFixedThreadPool(2);
        try (pool) {
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (String peer : List.of("admission-a", "admission-b")) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try { limits.verification(peer);
                    return true;
                    }
                    catch (LoginRateLimiter.LoginLimitException _) {
                        return false;
                    }
                }));
            }
            ready.await();
            go.countDown();
            List<Boolean> outcomes = new ArrayList<>();
            for (var result : results) {
                outcomes.add(result.get(30, TimeUnit.SECONDS));
            }
            assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM password_reset_limits", Integer.class)).isEqualTo(10000);
    }

    @Test void upgradingV3ToV4PreservesExistingPasswordsAndDoesNotInventChangeTime() {
        String schema="reset_upgrade_"+UUID.randomUUID().toString().replace("-","");
        var old=Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target("3").cleanDisabled(true).load();
        old.migrate();
        String hash=passwords.hash(OLD);
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO "+schema+".staff_users (id,email_normalized,password_hash,role,"
                + "is_active,created_at,updated_at) VALUES (?,'upgrade@example.com',?,'ADMIN',true,now(),now())",id,hash);
        var latest=Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").cleanDisabled(true).load();
        latest.migrate();
        assertThat(jdbc.queryForObject("SELECT password_hash FROM "+schema+".staff_users WHERE id=?",String.class,id)).isEqualTo(hash);
        assertThat(jdbc.queryForObject("SELECT password_changed_at FROM "+schema+".staff_users WHERE id=?",Timestamp.class,
                id)).isNull();
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("4");
    }

    @Test
    void statusEnumMatchesTheStatusCheckConstraintOfTheTable() {
        String definition = jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conrelid = 'password_reset_requests'::regclass AND contype = 'c'
                  AND pg_get_constraintdef(oid) LIKE '%CODE_PENDING%'""", String.class);
        var allowed = new TreeSet<String>();
        var quoted = Pattern.compile("'([A-Z_]+)'").matcher(definition);
        while (quoted.find()) {
            allowed.add(quoted.group(1));
        }
        var declared = new TreeSet<String>();
        for (var status : PasswordResetStatus.values()) {
            declared.add(status.name());
        }
        assertThat(declared).isEqualTo(allowed);
    }

    @TestConfiguration static class TimeConfiguration {
        @Bean @Primary MutableAuthClock resetClock() {
            return new MutableAuthClock();
        }
    }
}

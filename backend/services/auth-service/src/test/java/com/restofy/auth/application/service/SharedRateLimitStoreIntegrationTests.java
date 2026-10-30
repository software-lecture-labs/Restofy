package com.restofy.auth.application.service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.restofy.auth.infrastructure.security.LoginRateLimiter;
import com.restofy.auth.infrastructure.security.LoginRateLimiter.LoginLimitException;
import com.restofy.auth.infrastructure.security.MfaPeerLimiter;
import com.restofy.auth.infrastructure.security.PasswordResetCodes;
import com.restofy.auth.infrastructure.security.SharedRateLimitStore;
import com.restofy.auth.infrastructure.security.SharedRateLimitStore.Attempt;
import com.restofy.auth.infrastructure.security.SharedRateLimitStore.Family;
import com.restofy.auth.support.AuthSessionTestSupport;
import com.restofy.auth.support.AuthTestDatabaseInitializer;
import com.restofy.auth.support.MutableAuthClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"auth.bootstrap.dev-users.enabled=false", "auth.bootstrap.initial-admin.enabled=false",
        "auth.mail-outbox.relay-enabled=false"})
@ContextConfiguration(initializers = AuthTestDatabaseInitializer.class)
@Import(SharedRateLimitStoreIntegrationTests.TimeConfiguration.class)
class SharedRateLimitStoreIntegrationTests extends AuthSessionTestSupport {
    @Autowired private SharedRateLimitStore store;
    @Autowired private PasswordResetCodes codes;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MutableAuthClock clock;

    @BeforeEach
    void setup() {
        clean();
        clock.reset();
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM auth_login_limits");
        jdbc.update("DELETE FROM auth_mfa_peer_limits");
        jdbc.update("DELETE FROM password_reset_limits");
    }

    @Test
    void separatelyConstructedLoginLimitersShareNormalizedAccountAndPeerWindows() {
        LoginRateLimiter first = new LoginRateLimiter(store);
        LoginRateLimiter second = new LoginRateLimiter(store);
        for (int index = 0; index < 10; index++) {
            first.check(" Shared@EXAMPLE.test ", "192.0.2.1");
        }
        assertThatThrownBy(() -> second.check("shared@example.test", "192.0.2.1"))
                .isInstanceOf(LoginLimitException.class);
        assertThatCode(() -> second.check("shared@example.test", "192.0.2.2")).doesNotThrowAnyException();
        for (int index = 0; index < 10; index++) {
            first.check("malformed-email", "192.0.2.3");
        }
        assertThatThrownBy(() -> second.check(null, "192.0.2.3")).isInstanceOf(LoginLimitException.class);
        assertThat(jdbc.queryForList("SELECT bucket_key FROM auth_login_limits", String.class))
                .allSatisfy(key -> assertThat(key).matches("[a-f0-9]{64}"));
    }

    @Test
    void loginAccountCeilingIsSharedAcrossDifferentPeerAddresses() {
        LoginRateLimiter first = new LoginRateLimiter(store);
        LoginRateLimiter second = new LoginRateLimiter(store);
        for (int index = 0; index < 50; index++) {
            first.check("ceiling@example.test", "192.0.2." + index);
        }
        assertThatThrownBy(() -> second.check("ceiling@example.test", "198.51.100.1"))
                .isInstanceOfSatisfying(LoginLimitException.class, exception -> assertThat(exception.retryAfter()).isEqualTo(900));
        assertThat(attempts("auth_login_limits", "account:ceiling@example.test")).isEqualTo(50);
        clock.advance(900);
        assertThatCode(() -> second.check("ceiling@example.test", "198.51.100.1")).doesNotThrowAnyException();
    }

    @Test
    void mfaPeerWindowsAreSharedAndExpireAcrossLimiterObjects() {
        MfaPeerLimiter first = new MfaPeerLimiter(store);
        MfaPeerLimiter second = new MfaPeerLimiter(store);
        for (int index = 0; index < 30; index++) {
            first.check("192.0.2.50");
        }
        assertThatThrownBy(() -> second.check("192.0.2.50")).isInstanceOf(LoginLimitException.class);
        assertThatCode(() -> second.check("192.0.2.51")).doesNotThrowAnyException();
        clock.advance(60);
        assertThatCode(() -> second.check("192.0.2.50")).doesNotThrowAnyException();
    }

    @Test
    void blockedEarlierBudgetDoesNotConsumeLaterBudgetAndPreviousHitsAreCommitted() {
        Attempt peer = new Attempt("private-peer", 90, 1);
        Attempt account = new Attempt("private-account", 90, 1);
        assertThat(check(Family.LOGIN, peer, account)).isZero();
        assertThat(check(Family.LOGIN, peer, account)).isEqualTo(90);
        assertThat(attempts("auth_login_limits", account.key())).isEqualTo(1);
        Attempt otherPeer = new Attempt("other-private-peer", 90, 1);
        assertThat(check(Family.LOGIN, otherPeer, account)).isEqualTo(90);
        assertThat(attempts("auth_login_limits", otherPeer.key())).isEqualTo(1);
        assertThat(check(Family.MFA_PEER, peer)).isZero();
        assertThat(check(Family.RESET, peer)).isZero();
    }

    @Test
    void fortyParallelRequestsAdmitExactlyTenAcrossTheSharedWindow() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> results = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int index = 0; index < 40; index++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return check(Family.LOGIN, new Attempt("parallel-window", 90, 10));
                }));
            }
            start.countDown();
            int admitted = 0;
            for (Future<Long> result : results) {
                if (result.get(30, TimeUnit.SECONDS) == 0) {
                    admitted++;
                }
            }
            assertThat(admitted).isEqualTo(10);
        }
        assertThat(attempts("auth_login_limits", "parallel-window")).isEqualTo(10);
    }

    @Test
    void retryAfterTracksExpiryAndPastWindowsAreReplaced() {
        Attempt attempt = new Attempt("expiring-window", 90, 1);
        assertThat(check(Family.LOGIN, attempt)).isZero();
        clock.advance(89);
        assertThat(check(Family.LOGIN, attempt)).isEqualTo(1);
        jdbc.update("UPDATE auth_login_limits SET expires_at = ? WHERE bucket_key = ?",
                Timestamp.from(clock.instant().minusSeconds(1)), codes.digest("limit", attempt.key()));
        assertThat(check(Family.LOGIN, attempt)).isZero();
        assertThat(attempts("auth_login_limits", attempt.key())).isEqualTo(1);
    }

    @Test
    void loginAndMfaEvictOldestAtCapacityWhileResetFailsClosed() {
        seed("auth_login_limits", SharedRateLimitStore.MAX_BUCKETS);
        assertThat(check(Family.LOGIN, new Attempt("new-login-window", 90, 1))).isZero();
        assertThat(count("auth_login_limits")).isEqualTo(SharedRateLimitStore.MAX_BUCKETS);
        seed("auth_mfa_peer_limits", SharedRateLimitStore.MAX_BUCKETS);
        assertThat(check(Family.MFA_PEER, new Attempt("new-mfa-window", 90, 1))).isZero();
        assertThat(count("auth_mfa_peer_limits")).isEqualTo(SharedRateLimitStore.MAX_BUCKETS);
        seed("password_reset_limits", SharedRateLimitStore.MAX_BUCKETS);
        assertThat(check(Family.RESET, new Attempt("new-reset-window", 90, 1))).isEqualTo(60);
        assertThat(count("password_reset_limits")).isEqualTo(SharedRateLimitStore.MAX_BUCKETS);
    }

    @Test
    void existingResetBucketDigestAndCounterArePreserved() {
        String key = "requests:private@example.test";
        jdbc.update("INSERT INTO password_reset_limits(bucket_key, attempts, expires_at) VALUES (?, ?, ?)",
                codes.digest("limit", key), 4, Timestamp.from(clock.instant().plusSeconds(900)));
        assertThat(check(Family.RESET, new Attempt(key, 900, 10))).isZero();
        assertThat(attempts("password_reset_limits", key)).isEqualTo(5);
        assertThat(count("password_reset_limits")).isEqualTo(1);
    }

    @Test
    void unknownPeersAreBoundedAndAttemptToStringIsRedacted() {
        assertThat(SharedRateLimitStore.peerKey(null)).isEqualTo("unknown");
        assertThat(SharedRateLimitStore.peerKey("a".repeat(SharedRateLimitStore.MAX_PEER_LENGTH + 1))).isEqualTo("unknown");
        assertThat(SharedRateLimitStore.peerKey("192.0.2.7")).isEqualTo("192.0.2.7");
        assertThat(new Attempt("private-key", 60, 1).toString()).doesNotContain("private-key");
    }

    private long check(Family family, Attempt... attempts) {
        Long retryAfter = new TransactionTemplate(transactionManager).execute(_ -> {
            try {
                store.check(family, List.of(attempts));
                return 0L;
            } catch (LoginLimitException exception) {
                // Catch inside the outer transaction so earlier budgets commit on a later rejection.
                return exception.retryAfter();
            }
        });
        return retryAfter;
    }

    private int attempts(String table, String key) {
        return jdbc.queryForObject("SELECT attempts FROM " + table + " WHERE bucket_key = ?",
                Integer.class, codes.digest("limit", key));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private void seed(String table, int count) {
        jdbc.update("INSERT INTO " + table + " (bucket_key, attempts, expires_at) "
                        + "SELECT repeat(md5(i::text), 2), 1, ? FROM generate_series(1, ?) i",
                Timestamp.from(clock.instant().plusSeconds(900)), count);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean @Primary MutableAuthClock sharedLimitClock() {
            return new MutableAuthClock();
        }
    }
}

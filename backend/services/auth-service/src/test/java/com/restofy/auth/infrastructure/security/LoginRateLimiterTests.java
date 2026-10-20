package com.restofy.auth.infrastructure.security;

import com.restofy.auth.infrastructure.security.LoginRateLimiter.LoginLimitException;
import com.restofy.auth.support.MutableAuthClock;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginRateLimiterTests {
    private static final String OWNER = "admin@example.com";
    private static final String ATTACKER_PEER = "198.51.100.66";
    private static final String OWNER_PEER = "203.0.113.9";

    private final MutableAuthClock clock = new MutableAuthClock();
    private final LoginRateLimiter limiter = new LoginRateLimiter(clock);

    @Test
    void anAttackerExhaustingTheirOwnBudgetCannotLockTheOwnerOutFromAnotherAddress() {
        for (int attempt = 0; attempt < LoginRateLimiter.ATTEMPTS_PER_ACCOUNT_AND_PEER; attempt++) {
            limiter.check(OWNER, ATTACKER_PEER);
        }
        assertThatThrownBy(() -> limiter.check(OWNER, ATTACKER_PEER)).isInstanceOf(LoginLimitException.class);
        assertThatCode(() -> limiter.check(OWNER, OWNER_PEER)).doesNotThrowAnyException();
    }

    @Test
    void guessingSpreadOverManyAddressesStopsAtTheAccountCeilingWithoutBurningItWhileBlocked() {
        for (int peer = 0; peer < LoginRateLimiter.ATTEMPTS_PER_ACCOUNT; peer++) {
            limiter.check(OWNER, "192.0.2." + peer);
        }
        assertThatThrownBy(() -> limiter.check(OWNER, "192.0.2.250"))
                .isInstanceOfSatisfying(LoginLimitException.class,
                        exception -> assertThat(exception.retryAfter()).isEqualTo(LoginRateLimiter.WINDOW_SECONDS));
        clock.advance(LoginRateLimiter.WINDOW_SECONDS);
        assertThatCode(() -> limiter.check(OWNER, OWNER_PEER)).doesNotThrowAnyException();
    }

    @Test
    void aBlockedAddressDoesNotConsumeTheSharedAccountCeiling() {
        for (int attempt = 0; attempt < LoginRateLimiter.ATTEMPTS_PER_ACCOUNT_AND_PEER; attempt++) {
            limiter.check(OWNER, ATTACKER_PEER);
        }
        for (int blocked = 0; blocked < LoginRateLimiter.ATTEMPTS_PER_ACCOUNT; blocked++) {
            assertThatThrownBy(() -> limiter.check(OWNER, ATTACKER_PEER)).isInstanceOf(LoginLimitException.class);
        }
        assertThatCode(() -> limiter.check(OWNER, OWNER_PEER)).doesNotThrowAnyException();
    }

    @Test
    void floodingWithThousandsOfThrowAwayAccountsNeverRejectsAnHonestUser() {
        for (int account = 0; account < BoundedWindows.MAX_TRACKED_KEYS * 2; account++) {
            limiter.check("flood" + account + "@example.com", ATTACKER_PEER);
        }
        assertThatCode(() -> limiter.check("honest@example.com", OWNER_PEER)).doesNotThrowAnyException();
    }

    @Test
    void missingOrOversizedPeersShareOneBoundedKey() {
        limiter.check(OWNER, null);
        limiter.check(OWNER, "x".repeat(BoundedWindows.MAX_PEER_LENGTH + 1));
        assertThat(BoundedWindows.peerKey(null)).isEqualTo(BoundedWindows.peerKey("x".repeat(BoundedWindows.MAX_PEER_LENGTH + 1)));
    }

    @Test
    void trackedKeysNeverExceedTheConfiguredBoundAndTheOldestAreDroppedFirst() {
        BoundedWindows windows = new BoundedWindows(clock, 3);
        for (int key = 0; key < 10; key++) {
            assertThat(windows.hit("key-" + key, 1, LoginRateLimiter.WINDOW_SECONDS)).isZero();
            assertThat(windows.trackedKeys()).isLessThanOrEqualTo(3);
        }
        assertThat(windows.hit("key-9", 1, LoginRateLimiter.WINDOW_SECONDS)).isPositive();
        assertThat(windows.hit("key-0", 1, LoginRateLimiter.WINDOW_SECONDS)).isZero();
    }
}

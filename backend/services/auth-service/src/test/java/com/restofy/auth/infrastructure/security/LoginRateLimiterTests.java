package com.restofy.auth.infrastructure.security;

import com.restofy.auth.infrastructure.security.LoginRateLimiter.LoginLimitException;
import com.restofy.auth.support.InMemoryRateLimitStore;
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
    private final LoginRateLimiter limiter = new LoginRateLimiter(new InMemoryRateLimitStore(clock), true);

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
    void oneAddressHasOneBudgetAcrossEveryAccount() {
        for (int account = 0; account < LoginRateLimiter.ATTEMPTS_PER_PEER; account++) {
            limiter.check("fake" + account + "@example.com", ATTACKER_PEER);
        }
        assertThatThrownBy(() -> limiter.check("one-more@example.com", ATTACKER_PEER))
                .isInstanceOfSatisfying(LoginLimitException.class,
                        exception -> assertThat(exception.retryAfter()).isEqualTo(LoginRateLimiter.WINDOW_SECONDS));
        assertThatCode(() -> limiter.check(OWNER, OWNER_PEER)).doesNotThrowAnyException();
        clock.advance(LoginRateLimiter.WINDOW_SECONDS);
        assertThatCode(() -> limiter.check("one-more@example.com", ATTACKER_PEER)).doesNotThrowAnyException();
    }

    @Test
    void withoutKnownClientAddressesNoBudgetIsSharedByEveryone() {
        // Behind the Gateway with no trusted proxy configured every client has the Gateway's address.
        LoginRateLimiter unconfigured = new LoginRateLimiter(new InMemoryRateLimitStore(clock), false);
        for (int account = 0; account < 2 * LoginRateLimiter.ATTEMPTS_PER_PEER; account++) {
            unconfigured.check("user" + account + "@example.com", ATTACKER_PEER);
        }
        assertThatCode(() -> unconfigured.check(OWNER, ATTACKER_PEER)).doesNotThrowAnyException();
    }

    @Test
    void missingOrOversizedPeersShareOneBoundedKey() {
        limiter.check(OWNER, null);
        limiter.check(OWNER, "x".repeat(RateLimitStore.MAX_PEER_LENGTH + 1));
        assertThat(RateLimitStore.peerKey(null))
                .isEqualTo(RateLimitStore.peerKey("x".repeat(RateLimitStore.MAX_PEER_LENGTH + 1)));
        assertThat(RateLimitStore.peerKey("203.0.113.9")).isEqualTo("203.0.113.9");
    }

    @Test
    void anIpv6PeerIsLimitedAsItsWholeSlash64() {
        String first = RateLimitStore.peerKey("2001:db8:1:2:aaaa:bbbb:cccc:dddd");
        assertThat(first).isEqualTo("2001:db8:1:2:0:0:0:0/64")
                .isEqualTo(RateLimitStore.peerKey("2001:db8:1:2::1"));
        assertThat(RateLimitStore.peerKey("2001:db8:1:3::1")).isNotEqualTo(first);
        // IPv4 (also when written IPv4-mapped) and values that are not addresses are left alone.
        assertThat(RateLimitStore.peerKey("::ffff:203.0.113.9")).isEqualTo("::ffff:203.0.113.9");
        assertThat(RateLimitStore.peerKey("unknown")).isEqualTo("unknown");
    }

    @Test
    void addressesInOneIpv6Slash64ShareOneLoginBudget() {
        for (int attempt = 0; attempt < LoginRateLimiter.ATTEMPTS_PER_ACCOUNT_AND_PEER; attempt++) {
            limiter.check("owner@example.com", "2001:db8:1:2::" + Integer.toHexString(attempt + 1));
        }
        assertThatThrownBy(() -> limiter.check("owner@example.com", "2001:db8:1:2::ffff"))
                .isInstanceOf(LoginLimitException.class);
        assertThatCode(() -> limiter.check("owner@example.com", "2001:db8:1:3::1")).doesNotThrowAnyException();
    }
}

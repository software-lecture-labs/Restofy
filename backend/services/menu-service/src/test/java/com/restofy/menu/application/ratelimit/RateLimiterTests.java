package com.restofy.menu.application.ratelimit;

import com.restofy.menu.domain.exception.RateLimitExceededException;
import com.restofy.menu.domain.exception.RateLimitUnavailableException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** What the limiter decides from what the store answers, without a database: the fail-closed rules and the subjects. */
class RateLimiterTests {

    private static final RateLimitSettings.Policy POLICY = new RateLimitSettings.Policy(3, 60);
    private static final RateLimitSettings SETTINGS = new RateLimitSettings(POLICY, POLICY, POLICY, POLICY, POLICY, POLICY,
            POLICY, new RateLimitSettings.Policy(7, 30), 9);

    private record Call(RateLimitScope scope, String subject, int max, int window) { }

    private static final class FakeStore implements RateLimitStore {
        final List<Call> calls = new ArrayList<>();
        RateLimitDecision answer = new RateLimitDecision(true, 1, 60);
        RuntimeException failure;

        @Override
        public RateLimitDecision hit(RateLimitScope scope, String subject, int maxRequests, int windowSeconds) {
            calls.add(new Call(scope, subject, maxRequests, windowSeconds));
            if (failure != null) {
                throw failure;
            }
            return answer;
        }
    }

    private final FakeStore store = new FakeStore();
    private final RateLimiter limiter = new RateLimiter(store, SETTINGS);

    @Test
    void aRequestWithinTheBudgetPassesAndIsCountedWithItsScopesPolicy() {
        assertDoesNotThrow(() -> limiter.require(RateLimitScope.INTERNAL_RESTAURANT, "restaurant:x"));
        assertEquals(new Call(RateLimitScope.INTERNAL_RESTAURANT, "restaurant:x", 7, 30), store.calls.getFirst());
    }

    @Test
    void aRequestOverTheBudgetIsRefusedWithTheTimeLeft() {
        store.answer = new RateLimitDecision(false, 4, 42);
        RateLimitExceededException e = assertThrows(RateLimitExceededException.class,
                () -> limiter.require(RateLimitScope.ADMIN_WRITE, "actor:a:b"));
        assertEquals(42, e.retryAfterSeconds());
        assertEquals("Too many requests. Try again later.", e.getMessage());
    }

    @Test
    void aStoreThatCannotCountRefusesTheRequestNeverLetsItThrough() {
        store.failure = new IllegalStateException("database down, password=secret");
        RateLimitUnavailableException e = assertThrows(RateLimitUnavailableException.class,
                () -> limiter.require(RateLimitScope.PUBLIC_RESTAURANT, "restaurant:x"));
        assertEquals(9, e.retryAfterSeconds());
        assertEquals("Request limiting is temporarily unavailable.", e.getMessage());
    }

    @Test
    void aStoreThatAnswersNothingIsNotAnAllow() {
        store.answer = null;
        assertThrows(RateLimitUnavailableException.class, () -> limiter.require(RateLimitScope.PUBLIC_UNVERIFIED, "peer:1.2.3.4"));
    }

    @Test
    void theRetryAfterIsNeverBelowOneSecond() {
        assertEquals(1, new RateLimitExceededException(0).retryAfterSeconds());
        assertEquals(1, new RateLimitExceededException(-5).retryAfterSeconds());
        assertEquals(1, new RateLimitUnavailableException(0).retryAfterSeconds());
    }

    @Test
    void theInternalPolicyIsCountedPerVerifiedRestaurantInItsOwnScope() {
        UUID restaurant = UUID.randomUUID();
        limiter.requireServiceCall(restaurant);
        assertEquals(new Call(RateLimitScope.INTERNAL_RESTAURANT, "restaurant:" + restaurant, 7, 30), store.calls.getFirst());
    }

    @Test
    void everyScopeHasAPolicy() {
        for (RateLimitScope scope : RateLimitScope.values()) {
            assertEquals(scope == RateLimitScope.INTERNAL_RESTAURANT ? 7 : 3, SETTINGS.policy(scope).maxRequests(), scope.name());
        }
    }

    @Test
    void subjectsAreBuiltOnlyFromVerifiedIdsAndASanePeerAddress() {
        UUID restaurant = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
        UUID user = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
        assertEquals("restaurant:aaaaaaaa-0000-0000-0000-000000000001", RateLimitSubjects.restaurant(restaurant));
        assertEquals("actor:aaaaaaaa-0000-0000-0000-000000000001:bbbbbbbb-0000-0000-0000-000000000002",
                RateLimitSubjects.actor(restaurant, user));
        assertEquals("peer:203.0.113.9", RateLimitSubjects.peer("203.0.113.9"));
        assertEquals("peer:2001:db8::1", RateLimitSubjects.peer("2001:db8::1"));
        assertEquals("peer:fe80::1%0", RateLimitSubjects.peer("fe80::1%0"));
        for (String hostile : new String[] {null, "", "1.2.3.4, 5.6.7.8", "evil'; DROP TABLE x", "a".repeat(65), "1.2.3.4\n", "host name"}) {
            assertEquals("peer:unknown", RateLimitSubjects.peer(hostile), String.valueOf(hostile));
        }
    }
}

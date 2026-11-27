package com.restofy.auth.infrastructure.security;

import java.util.UUID;

import com.restofy.auth.infrastructure.security.LoginRateLimiter.LoginLimitException;
import com.restofy.auth.support.InMemoryRateLimitStore;
import com.restofy.auth.support.MutableAuthClock;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StaffAdministrationLimiterTests {
    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID OTHER_ADMIN = UUID.randomUUID();

    private final MutableAuthClock clock = new MutableAuthClock();
    private final StaffAdministrationLimiter limiter = new StaffAdministrationLimiter(new InMemoryRateLimitStore(clock));

    @Test
    void oneAdministratorGetsAFixedBudgetPerWindowAndTheNextWindowStartsAfresh() {
        for (int change = 0; change < StaffAdministrationLimiter.CHANGES_PER_WINDOW; change++) {
            limiter.check(ADMIN);
        }
        assertThatThrownBy(() -> limiter.check(ADMIN)).isInstanceOfSatisfying(LoginLimitException.class,
                exception -> assertThat(exception.retryAfter()).isEqualTo(StaffAdministrationLimiter.WINDOW_SECONDS));
        clock.advance(StaffAdministrationLimiter.WINDOW_SECONDS);
        assertThatCode(() -> limiter.check(ADMIN)).doesNotThrowAnyException();
    }

    @Test
    void anExhaustedAdministratorDoesNotBlockAnotherOne() {
        for (int change = 0; change < StaffAdministrationLimiter.CHANGES_PER_WINDOW; change++) {
            limiter.check(ADMIN);
        }
        assertThatThrownBy(() -> limiter.check(ADMIN)).isInstanceOf(LoginLimitException.class);
        assertThatCode(() -> limiter.check(OTHER_ADMIN)).doesNotThrowAnyException();
    }
}

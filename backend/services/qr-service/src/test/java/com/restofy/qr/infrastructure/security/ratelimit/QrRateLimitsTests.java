package com.restofy.qr.infrastructure.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.restofy.qr.infrastructure.security.ratelimit.QrRateLimits.FailureBudget;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The ADR-QR-002 K7 budgets: which bucket each route uses and its numbers. */
class QrRateLimitsTests {
    private static final UUID RESTAURANT = UUID.randomUUID();
    private static final UUID SUBJECT = UUID.randomUUID();
    private final RateLimitStore store = mock(RateLimitStore.class);
    private final QrRateLimits limits = new QrRateLimits(store, true);

    @Test
    void eachVerifiedScopeUsesItsOwnBucketAndNumbers() {
        limits.scanOf(RESTAURANT, SUBJECT);
        limits.guestCreatedAt(RESTAURANT, SUBJECT);
        limits.guestCall(RESTAURANT, SUBJECT, true);
        limits.guestCall(RESTAURANT, SUBJECT, false);
        limits.staff(RESTAURANT, SUBJECT, false);
        limits.staff(RESTAURANT, SUBJECT, true);

        verify(store).hit("scan-qr:" + RESTAURANT + "|" + SUBJECT, 60, 60);
        verify(store).hit("open-table:" + RESTAURANT + "|" + SUBJECT, 20, 600);
        verify(store).hit("guest-activity:" + RESTAURANT + "|" + SUBJECT, 30, 60);
        verify(store).hit("guest-csrf:" + RESTAURANT + "|" + SUBJECT, 30, 60);
        verify(store).hit("staff-read:" + RESTAURANT + "|" + SUBJECT, 300, 60);
        verify(store).hit("staff-write:" + RESTAURANT + "|" + SUBJECT, 60, 60);
        verifyNoMoreInteractions(store);
    }

    @Test
    void failureBudgetsAreReadBeforeAndCountedOnlyAfterAnUnknownCredential() {
        for (FailureBudget budget : FailureBudget.values()) {
            assertThat(limits.failureRetryAfter(budget, "203.0.113.9")).isZero();
            limits.failed(budget, "203.0.113.9");
        }
        verify(store).spent("scan-fail:203.0.113.9", 30);
        verify(store).hit("scan-fail:203.0.113.9", 30, 60);
        verify(store).spent("open-fail:203.0.113.9", 10);
        verify(store).hit("open-fail:203.0.113.9", 10, 600);
        verify(store).spent("guest-fail:203.0.113.9", 30);
        verify(store).hit("guest-fail:203.0.113.9", 30, 60);
        verifyNoMoreInteractions(store);
    }

    @Test
    void aSharedRestaurantAddressIsNeverChargedForSuccessfulGuests() {
        // 50 guests on one Wi-Fi address, each with a valid QR code: only the pre-check runs, nothing is counted.
        for (int guest = 0; guest < 50; guest++) {
            assertThat(limits.failureRetryAfter(FailureBudget.OPEN, "203.0.113.9")).isZero();
        }
        verify(store, org.mockito.Mockito.times(50)).spent("open-fail:203.0.113.9", 10);
        verifyNoMoreInteractions(store);
    }

    @Test
    void ipv6ClientsShareTheirSlash64Budget() {
        limits.failed(FailureBudget.SCAN, "2001:db8:1:2:aaaa::1");
        verify(store).hit("scan-fail:2001:db8:1:2:0:0:0:0/64", 30, 60);
    }

    @Test
    void withoutAKnownClientAddressTheFailureBudgetsAreSkipped() {
        var anonymous = new QrRateLimits(store, false);
        for (FailureBudget budget : FailureBudget.values()) {
            assertThat(anonymous.failureRetryAfter(budget, "10.213.0.10")).isZero();
            anonymous.failed(budget, "10.213.0.10");
        }
        verifyNoInteractions(store);
        anonymous.guestCall(RESTAURANT, SUBJECT, true);
        verify(store).hit("guest-activity:" + RESTAURANT + "|" + SUBJECT, 30, 60);
    }

    @Test
    void aSpentFailureBudgetReportsItsRetryAfterWithoutRefusing() {
        // Refusing is left to the exception handler, and only for unknown credentials.
        when(store.spent("guest-fail:203.0.113.9", 30)).thenReturn(12L);
        assertThat(limits.failureRetryAfter(FailureBudget.GUEST, "203.0.113.9")).isEqualTo(12);
    }

    @Test
    void anUnusableStoreIsNotSwallowed() {
        when(store.hit(anyString(), anyInt(), anyLong())).thenThrow(new RateLimitUnavailableException(null));
        when(store.spent(anyString(), anyInt())).thenThrow(new RateLimitUnavailableException(null));
        assertThatThrownBy(() -> limits.staff(RESTAURANT, SUBJECT, true)).isInstanceOf(RateLimitUnavailableException.class);
        assertThatThrownBy(() -> limits.failureRetryAfter(FailureBudget.SCAN, "203.0.113.9"))
                .isInstanceOf(RateLimitUnavailableException.class);
    }
}

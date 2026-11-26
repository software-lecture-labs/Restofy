package com.restofy.qr.infrastructure.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.restofy.qr.domain.exception.RateLimitExceededException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The ADR-QR-002 K7 budgets: which bucket each route uses and its numbers. */
class QrRateLimitsTests {
    private static final UUID RESTAURANT = UUID.randomUUID();
    private static final UUID SUBJECT = UUID.randomUUID();
    private final RateLimitStore store = mock(RateLimitStore.class);
    private final QrRateLimits limits = new QrRateLimits(store, true);

    @Test
    void eachRouteUsesItsOwnBucketAndNumbers() {
        limits.scanFrom("203.0.113.9");
        limits.scanOf(RESTAURANT, SUBJECT);
        limits.openFrom("203.0.113.9");
        limits.guestCreatedAt(RESTAURANT, SUBJECT);
        limits.guestCall("203.0.113.9", "token", true);
        limits.guestCall("203.0.113.9", "token", false);
        limits.staff(RESTAURANT, SUBJECT, false);
        limits.staff(RESTAURANT, SUBJECT, true);

        verify(store).hit("scan-peer:203.0.113.9", 30, 60);
        verify(store).hit("scan-qr:" + RESTAURANT + "|" + SUBJECT, 60, 60);
        verify(store).hit("open-peer:203.0.113.9", 10, 600);
        verify(store).hit("open-table:" + RESTAURANT + "|" + SUBJECT, 20, 600);
        verify(store, org.mockito.Mockito.times(2)).hit("guest-peer:203.0.113.9", 300, 60);
        verify(store).hit("guest-activity:token", 30, 60);
        verify(store).hit("guest-csrf:token", 30, 60);
        verify(store).hit("staff-read:" + RESTAURANT + "|" + SUBJECT, 300, 60);
        verify(store).hit("staff-write:" + RESTAURANT + "|" + SUBJECT, 60, 60);
    }

    @Test
    void ipv6ClientsShareTheirSlash64Budget() {
        limits.scanFrom("2001:db8:1:2:aaaa::1");
        verify(store).hit("scan-peer:2001:db8:1:2:0:0:0:0/64", 30, 60);
    }

    @Test
    void withoutAKnownClientAddressThePerAddressBudgetsAreSkipped() {
        var anonymous = new QrRateLimits(store, false);
        anonymous.scanFrom("10.213.0.10");
        anonymous.openFrom("10.213.0.10");
        verifyNoInteractions(store);
        anonymous.guestCall("10.213.0.10", "token", true);
        verify(store, never()).hit(org.mockito.ArgumentMatchers.startsWith("guest-peer:"), anyInt(), anyLong());
        verify(store).hit("guest-activity:token", 30, 60);
    }

    @Test
    void aSpentAddressBudgetStopsBeforeTheCredentialBucketIsCreated() {
        when(store.hit("guest-peer:203.0.113.9", 300, 60)).thenReturn(12L);
        assertThatThrownBy(() -> limits.guestCall("203.0.113.9", "invented", true))
                .isInstanceOfSatisfying(RateLimitExceededException.class,
                        limited -> org.assertj.core.api.Assertions.assertThat(limited.retryAfterSeconds()).isEqualTo(12));
        verify(store, never()).hit(org.mockito.ArgumentMatchers.startsWith("guest-activity:"), anyInt(), anyLong());
    }

    @Test
    void anUnusableStoreIsNotSwallowed() {
        when(store.hit(anyString(), anyInt(), anyLong())).thenThrow(new RateLimitUnavailableException(null));
        assertThatThrownBy(() -> limits.staff(RESTAURANT, SUBJECT, true)).isInstanceOf(RateLimitUnavailableException.class);
    }
}

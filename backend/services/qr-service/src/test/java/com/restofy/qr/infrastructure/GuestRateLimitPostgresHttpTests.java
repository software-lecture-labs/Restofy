package com.restofy.qr.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.restofy.qr.api.dto.request.ChangeQrCodeActiveRequest;
import com.restofy.qr.api.dto.request.CreateTableRequest;
import com.restofy.qr.api.dto.response.CreatedQrCodeResponse;
import com.restofy.qr.api.dto.response.OpenedGuestSessionResponse;
import com.restofy.qr.application.service.GuestSessionOpeningService;
import com.restofy.qr.application.service.QrCodeCreationService;
import com.restofy.qr.application.service.QrCodeLifecycleService;
import com.restofy.qr.application.service.TableManagementService;
import com.restofy.qr.infrastructure.persistence.repository.GuestSessionRepository;
import com.restofy.qr.infrastructure.security.TableManagementAccess;
import com.restofy.qr.infrastructure.security.ratelimit.RateLimitStore;
import com.restofy.qr.infrastructure.security.revocation.StaffRevocationStore;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * ADR-QR-002 K7 with real QR codes and guests behind the full security chain: a spent address budget never blocks a
 * known QR code or guest (one person on the restaurant's Wi-Fi cannot make new customers wait), only unknown credentials
 * get 429, and the per-guest bucket exists only for a verified guest. The counter store is mocked to put the budgets in
 * a chosen state; its PostgreSQL behaviour is covered by PostgresRateLimitStoreTests.
 */
@SpringBootTest(properties = {"qr.payload.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "qr.proxy.trusted-cidrs=127.0.0.1/32"})
class GuestRateLimitPostgresHttpTests extends com.restofy.qr.support.Q5TestSecuritySupport {
    private static final String ORIGIN = "https://restofy.example";
    private static final String CSRF = "B".repeat(43);
    private static final String UNKNOWN = "A".repeat(43);
    @Autowired WebApplicationContext context;
    @Autowired GuestSessionOpeningService opening;
    @Autowired QrCodeCreationService creation;
    @Autowired QrCodeLifecycleService lifecycle;
    @Autowired TableManagementService tables;
    @Autowired GuestSessionRepository guests;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean RateLimitStore store;
    @MockitoBean StaffRevocationStore revocations;
    // Test-only management context for creating the fixtures; the guest routes never use it.
    @MockitoBean TableManagementAccess access;
    @MockitoBean Clock clock;
    private final AtomicReference<UUID> restaurant = new AtomicReference<>();
    private MockMvc http;

    @BeforeEach
    void setup() {
        reset(store);
        restaurant.set(UUID.randomUUID());
        when(access.requireRestaurantId()).thenAnswer(invocation -> restaurant.get());
        when(clock.instant()).thenAnswer(invocation -> Instant.now());
        http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private CreatedQrCodeResponse create(String number) {
        var code = creation.create(tables.create(new CreateTableRequest(number, null, null)).id());
        when(clock.instant()).thenReturn(code.issuedAt().plusSeconds(1).truncatedTo(ChronoUnit.MICROS));
        return code;
    }

    private ResultActions scan(String path, String qrToken) throws Exception {
        return http.perform(post(path).header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"qrToken\":\"" + qrToken + "\"}"));
    }

    private ResultActions activity(String guestToken) throws Exception {
        return http.perform(post("/api/v1/qr/guest-sessions/activity").header("Origin", ORIGIN).header("X-Guest-CSRF", CSRF)
                .cookie(new Cookie("restofy_guest", guestToken), new Cookie("restofy_guest_csrf", CSRF)));
    }

    private ResultActions csrf(String guestToken) throws Exception {
        return http.perform(get("/api/v1/qr/guest-sessions/csrf").header("Origin", ORIGIN)
                .cookie(new Cookie("restofy_guest", guestToken), new Cookie("restofy_guest_csrf", CSRF)));
    }

    private void spendEveryAddressBudget() {
        when(store.spent(anyString(), anyInt())).thenReturn(17L);
    }

    @Test
    void aSpentAddressBudgetStillServesValidQrCodesAndGuests() throws Exception {
        var code = create("1");
        OpenedGuestSessionResponse guest = opening.open(code.qrToken());
        spendEveryAddressBudget();

        scan("/api/v1/qr/validate", code.qrToken()).andExpect(status().isOk());
        scan("/api/v1/qr/table-sessions/open", code.qrToken()).andExpect(status().isCreated());
        activity(guest.guestToken()).andExpect(status().isOk());
        csrf(guest.guestToken()).andExpect(status().isOk());

        verify(store).spent("scan-fail:127.0.0.1", 30);
        verify(store).spent("open-fail:127.0.0.1", 10);
        // The spent budget was read, but known credentials neither pay for it nor are refused by it.
        verify(store, never()).hit(startsWith("scan-fail:"), anyInt(), anyLong());
        verify(store, never()).hit(startsWith("open-fail:"), anyInt(), anyLong());
        verify(store, never()).hit(startsWith("guest-fail:"), anyInt(), anyLong());
    }

    @Test
    void withASpentBudgetKnownButUnusableCredentialsKeepTheGeneric404AndOnlyUnknownOnesGet429() throws Exception {
        var code = create("1");
        OpenedGuestSessionResponse guest = opening.open(code.qrToken());
        var inactive = create("2");
        lifecycle.changeActive(inactive.tableId(), inactive.id(), new ChangeQrCodeActiveRequest(false, 0L));
        jdbc.update("update guest_sessions set status='ENDED',ended_at=last_activity_at where id=?", guest.guestSession().id());
        spendEveryAddressBudget();

        for (String path : new String[]{"/api/v1/qr/validate", "/api/v1/qr/table-sessions/open"}) {
            scan(path, inactive.qrToken()).andExpect(status().isNotFound());
            scan(path, UNKNOWN).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "17"));
        }
        activity(guest.guestToken()).andExpect(status().isNotFound());
        activity(UNKNOWN).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "17"));
        csrf(UNKNOWN).andExpect(status().isTooManyRequests());
        // Only the known (ended) guest has a bucket; the unknown credential opened none.
        verify(store).hit("guest-activity:" + guest.guestSession().restaurantId() + "|" + guest.guestSession().id(), 30, 60L);
        verify(store).hit(startsWith("guest-activity:"), anyInt(), anyLong());
        verify(store, never()).hit(startsWith("guest-csrf:"), anyInt(), anyLong());
    }

    @Test
    void aVerifiedGuestIsCountedUnderItsOwnIdAndGets429WithoutRenewal() throws Exception {
        var code = create("1");
        OpenedGuestSessionResponse guest = opening.open(code.qrToken());
        var before = guests.findById(guest.guestSession().id()).orElseThrow();
        when(store.hit(startsWith("guest-activity:"), anyInt(), anyLong())).thenReturn(9L);

        activity(guest.guestToken()).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "9"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        csrf(guest.guestToken()).andExpect(status().isOk());

        String key = guest.guestSession().restaurantId() + "|" + guest.guestSession().id();
        verify(store).hit("guest-activity:" + key, 30, 60L);
        verify(store).hit("guest-csrf:" + key, 30, 60L);
        assertThat(guests.findById(guest.guestSession().id()).orElseThrow().getVersion()).isEqualTo(before.getVersion());
    }
}

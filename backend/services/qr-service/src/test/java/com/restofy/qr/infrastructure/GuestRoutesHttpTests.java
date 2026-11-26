package com.restofy.qr.infrastructure;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.restofy.qr.infrastructure.security.ratelimit.RateLimitStore;
import com.restofy.qr.infrastructure.security.revocation.StaffRevocationStore;
import com.restofy.qr.infrastructure.security.ratelimit.RateLimitUnavailableException;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Full security chain after ADR-QR-002 K7: the guest routes are open, still guarded by the K3-K6 proofs, and every
 * request that passes those proofs is counted. The counter store is mocked here; its behaviour against PostgreSQL is
 * covered by PostgresRateLimitStoreTests. MockMvc's socket peer is 127.0.0.1, configured here as the trusted proxy.
 */
@SpringBootTest(properties = {"qr.payload.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "qr.proxy.trusted-cidrs=127.0.0.1/32"})
class GuestRoutesHttpTests extends com.restofy.qr.support.Q5TestSecuritySupport {
    private static final String ORIGIN = "https://restofy.example";
    private static final String TOKEN = "A".repeat(43);
    private static final String CSRF = "B".repeat(43);
    private static final UUID RESTAURANT = UUID.fromString("6c54daf0-9340-4a1c-981d-f9acc0261779");
    @Autowired WebApplicationContext context;
    @MockitoBean RateLimitStore store;
    // The revocation guard needs a bootstrapped durable primary; it is covered by the staff HTTP tests.
    @MockitoBean StaffRevocationStore revocations;
    private MockMvc http;

    @BeforeEach
    void setup() {
        reset(store);
        http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void completeGuestProofsNowReachTheControllerAndAnUnknownGuestGetsTheGeneric404() throws Exception {
        http.perform(post("/api/v1/qr/guest-sessions/activity").header("Origin", ORIGIN).header("X-Guest-CSRF", CSRF)
                .cookie(new Cookie("restofy_guest", TOKEN), new Cookie("restofy_guest_csrf", CSRF)))
                .andExpect(status().isNotFound()).andExpect(header().exists("Set-Cookie"));
        http.perform(get("/api/v1/qr/guest-sessions/csrf").header("Origin", ORIGIN)
                .cookie(new Cookie("restofy_guest", TOKEN), new Cookie("restofy_guest_csrf", CSRF)))
                .andExpect(status().isNotFound());
        verify(store).hit(startsWith("guest-activity:"), eq(30), eq(60L));
        verify(store).hit(startsWith("guest-csrf:"), eq(30), eq(60L));
    }

    @Test
    void sameOriginCsrfReadWithoutOriginIsAllowedThroughTheProofs() throws Exception {
        http.perform(get("/api/v1/qr/guest-sessions/csrf").cookie(new Cookie("restofy_guest", TOKEN)))
                .andExpect(status().isNotFound());
    }

    @Test
    void anUnknownQrCostsOnlyTheAddressBudget() throws Exception {
        http.perform(post("/api/v1/qr/validate").header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qrToken\":\"" + "Q".repeat(60) + "\"}"))
                .andExpect(status().is4xxClientError());
        verify(store).hit("scan-peer:127.0.0.1", 30, 60L);
        verify(store, never()).hit(startsWith("scan-qr:"), anyInt(), anyLong());
    }

    @Test
    void rejectedProofsAreNotCounted() throws Exception {
        http.perform(get("/api/v1/qr/guest-sessions/csrf").header("Origin", "https://evil.example")
                .cookie(new Cookie("restofy_guest", TOKEN)))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Set-Cookie"));
        http.perform(post("/api/v1/qr/guest-sessions/activity").header("Origin", ORIGIN).header("X-Guest-CSRF", "C".repeat(43))
                .cookie(new Cookie("restofy_guest", TOKEN), new Cookie("restofy_guest_csrf", CSRF)))
                .andExpect(status().isForbidden());
        http.perform(post("/api/v1/qr/validate").contentType(MediaType.APPLICATION_JSON).content("{\"qrToken\":\"x\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(store);
    }

    @Test
    void staffCsrfProofIsNotAGuestProofAndTheOldBodyTokenRouteIsGone() throws Exception {
        http.perform(post("/api/v1/qr/guest-sessions/activity").header("Origin", ORIGIN).header("X-CSRF-TOKEN", CSRF)
                .cookie(new Cookie("restofy_csrf", CSRF)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"guestToken\":\"" + TOKEN + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anExhaustedBudgetGets429WithRetryAfterAndNoCookie() throws Exception {
        when(store.hit(anyString(), anyInt(), anyLong())).thenReturn(17L);
        for (String path : new String[]{"/api/v1/qr/validate", "/api/v1/qr/table-sessions/open"}) {
            http.perform(post(path).header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON).content("{\"qrToken\":\"x\"}"))
                    .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "17"))
                    .andExpect(header().doesNotExist("Set-Cookie"));
        }
        http.perform(post("/api/v1/qr/guest-sessions/activity").header("Origin", ORIGIN).header("X-Guest-CSRF", CSRF)
                        .cookie(new Cookie("restofy_guest", TOKEN), new Cookie("restofy_guest_csrf", CSRF)))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "17"));
    }

    @Test
    void anUnusableStoreFailsClosedWith503() throws Exception {
        when(store.hit(anyString(), anyInt(), anyLong())).thenThrow(new RateLimitUnavailableException(new IllegalStateException()));
        http.perform(post("/api/v1/qr/validate").header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qrToken\":\"x\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(header().doesNotExist("Set-Cookie"));
        http.perform(get("/api/v1/qr/guest-sessions/csrf").cookie(new Cookie("restofy_guest", TOKEN)))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void forwardedForIsReadOnlyFromTheTrustedProxy() throws Exception {
        http.perform(post("/api/v1/qr/validate").header("Origin", ORIGIN).header("X-Forwarded-For", "203.0.113.9")
                .contentType(MediaType.APPLICATION_JSON).content("{\"qrToken\":\"x\"}"));
        verify(store).hit("scan-peer:203.0.113.9", 30, 60L);

        http.perform(post("/api/v1/qr/validate").header("Origin", ORIGIN).header("X-Forwarded-For", "203.0.113.10")
                .with(request -> { request.setRemoteAddr("198.51.100.7"); return request; })
                .contentType(MediaType.APPLICATION_JSON).content("{\"qrToken\":\"x\"}"));
        verify(store).hit("scan-peer:198.51.100.7", 30, 60L);
        verify(store, never()).hit(eq("scan-peer:203.0.113.10"), anyInt(), anyLong());
    }

    @Test
    void verifiedStaffRequestsHaveTheirOwnReadBudget() throws Exception {
        UUID user = UUID.randomUUID();
        when(store.hit(startsWith("staff-read:"), anyInt(), anyLong())).thenReturn(5L);
        http.perform(get("/api/v1/qr/tables").cookie(new Cookie("restofy_access", staffToken(user))))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "5"));
        verify(store).hit("staff-read:" + RESTAURANT + "|" + user, 300, 60L);
    }

    private static String staffToken(UUID user) {
        Instant now = Instant.now();
        return Jwts.builder().issuer("restofy-auth").subject(user.toString()).audience().add("restofy-staff-api").and()
                .issuedAt(Date.from(now)).notBefore(Date.from(now)).expiration(Date.from(now.plusSeconds(300)))
                .id(UUID.randomUUID().toString()).claim("sid", UUID.randomUUID().toString()).claim("role", "ADMIN")
                .claim("restaurant_id", RESTAURANT.toString()).claim("token_type", "access")
                .claim("auth_state", "AUTHENTICATED").claim("auth_policy", "q5-idle-v1")
                .signWith(STAFF.getPrivate(), Jwts.SIG.RS256).compact();
    }
}

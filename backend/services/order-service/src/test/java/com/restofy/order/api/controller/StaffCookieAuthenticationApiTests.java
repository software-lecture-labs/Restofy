package com.restofy.order.api.controller;

import com.restofy.order.application.port.AuthContextPort;
import com.restofy.order.application.port.GuestSessionContext;
import com.restofy.order.application.port.QrSessionPort;
import com.restofy.order.application.port.StaffActor;
import com.restofy.order.domain.entity.Order;
import com.restofy.order.domain.entity.OrderItem;
import com.restofy.order.domain.enums.StaffRole;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import com.restofy.order.domain.pricing.PriceCalculator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"spring.profiles.active=order-auth", "CORS_ALLOWED_ORIGINS=https://staff.example.test",
        "spring.config.import=classpath:application-test.properties",
        "spring.datasource.url=jdbc:tc:postgresql:17-alpine:///restofy_order_auth_test",
        "spring.datasource.hikari.maximum-pool-size=2"})
@ActiveProfiles("order-auth")
@AutoConfigureMockMvc
class StaffCookieAuthenticationApiTests {
    private static final String ORIGIN = "https://staff.example.test";
    private static final String REPORT = "/api/v1/admin/order-cancellations";
    private static final String ACCESS = "test-staff-access";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";
    private static final String GUEST_TOKEN = "g".repeat(43);
    private static final String GUEST_CSRF = "p".repeat(43);
    private static final String PROOF = proofValue(Instant.now().plusSeconds(900));
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    @MockitoBean
    private AuthContextPort auth;
    @MockitoBean
    private QrSessionPort qr;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private TransactionTemplate tx;
    @PersistenceContext
    private EntityManager em;

    @Test
    void signedContextDeterminesRoleAndTenant() throws Exception {
        var source = new Order.Source(TENANT, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var order = new Order(UUID.randomUUID(), "ORD-000001", source, "TRY",
                new PriceCalculator.OrderAmounts(10, 0, 10), null, Instant.now());
        tx.executeWithoutResult(status -> em.persist(order));
        String orderPath = "/api/v1/orders/" + order.getId();
        when(auth.resolveStaff(ACCESS)).thenReturn(Optional.of(staff(StaffRole.ADMIN)));
        mvc.perform(get(orderPath).cookie(access())).andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(order.getId().toString()));

        when(auth.resolveStaff(ACCESS)).thenReturn(Optional.of(new StaffActor(USER, UUID.randomUUID(), StaffRole.ADMIN)));
        mvc.perform(get(orderPath).cookie(access()).header("X-Restaurant-Id", TENANT))
                .andExpect(status().isNotFound());

        when(auth.resolveStaff(ACCESS)).thenReturn(Optional.of(staff(StaffRole.KITCHEN)));
        mvc.perform(get(REPORT).cookie(access()).header("X-Role", "ADMIN")).andExpect(status().isForbidden());
    }

    @Test
    void legacyStaffHeaderAndAmbiguousCookiesDoNotAuthenticate() throws Exception {
        mvc.perform(get(REPORT).header(HttpHeaders.AUTHORIZATION, "Staff test-value").cookie(access()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "StaffSession realm=\"order\""));
        mvc.perform(get(REPORT).cookie(access(), new Cookie("restofy_access", "other")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(REPORT).header(HttpHeaders.AUTHORIZATION, "Bearer guest", "Staff test-value").cookie(access()))
                .andExpect(status().isBadRequest());
        mvc.perform(fulfillment().header(HttpHeaders.AUTHORIZATION, "Staff test-value"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(auth, qr);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing origin", "wrong origin", "duplicate origin", "missing header",
            "wrong header", "duplicate header", "missing cookie", "duplicate cookie"})
    void cookieBasedWritesRequireExactlyOneOriginAndMatchingBrowserProof(String failure) throws Exception {
        var request = fulfillment();
        switch (failure) {
            case "missing origin" -> { /* Omit Origin for this case. */ }
            case "wrong origin" -> request.header(HttpHeaders.ORIGIN, "https://other.example.test");
            case "duplicate origin" -> request.header(HttpHeaders.ORIGIN, ORIGIN, ORIGIN);
            default -> request.header(HttpHeaders.ORIGIN, ORIGIN);
        }
        switch (failure) {
            case "missing header" -> { /* Omit the CSRF header for this case. */ }
            case "wrong header" -> request.header(CSRF_HEADER, "1." + "a".repeat(43) + "." + "b".repeat(43));
            case "duplicate header" -> request.header(CSRF_HEADER, PROOF, PROOF);
            default -> request.header(CSRF_HEADER, PROOF);
        }
        switch (failure) {
            case "missing cookie" -> { /* Omit the CSRF cookie for this case. */ }
            case "duplicate cookie" -> request.cookie(proof(), proof());
            default -> request.cookie(proof());
        }
        mvc.perform(request).andExpect(status().isForbidden());
        verifyNoInteractions(auth);
    }

    @Test
    void credentialedPreflightIsLimitedToConfiguredOriginsAndStaffRoutes() throws Exception {
        String path = "/api/v1/orders/" + UUID.randomUUID() + "/fulfillment-status";
        mvc.perform(preflight(path, ORIGIN)).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
        mvc.perform(preflight(path, "https://other.example.test")).andExpect(status().isForbidden());
        mvc.perform(preflight("/api/v1/carts/current", ORIGIN))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        verifyNoInteractions(auth, qr);
    }

    @Test
    void aValidBrowserProofReachesTheStaffWriteOperation() throws Exception {
        when(auth.resolveStaff(ACCESS)).thenReturn(Optional.of(staff(StaffRole.WAITER)));
        mvc.perform(fulfillment().cookie(proof()).header(HttpHeaders.ORIGIN, ORIGIN).header(CSRF_HEADER, PROOF))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void aGuestCookieDoesNotChangeCsrfRequirementsOnAStaffOnlyWrite() throws Exception {
        when(auth.resolveStaff(ACCESS)).thenReturn(Optional.of(staff(StaffRole.WAITER)));
        var guest = new Cookie("restofy_guest", GUEST_TOKEN);
        mvc.perform(fulfillment().cookie(guest).header(HttpHeaders.ORIGIN, ORIGIN))
                .andExpect(status().isForbidden());
        verifyNoInteractions(auth, qr);

        mvc.perform(fulfillment().cookie(guest, proof()).header(HttpHeaders.ORIGIN, ORIGIN).header(CSRF_HEADER, PROOF))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        verify(auth).resolveStaff(ACCESS);
        verifyNoInteractions(qr);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void expiredBrowserProofIsRejectedEvenWhenHeaderMatchesCookie(long expiresInSeconds) throws Exception {
        String expired = proofValue(Instant.now().plusSeconds(expiresInSeconds));
        mvc.perform(fulfillment().cookie(new Cookie("restofy_csrf", expired)).header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(CSRF_HEADER, expired))
                .andExpect(status().isForbidden());
        verifyNoInteractions(auth);
    }

    @Test
    void invalidOrUnavailableStaffSessionsFailClosed() throws Exception {
        when(auth.resolveStaff(ACCESS)).thenReturn(Optional.empty())
                .thenThrow(new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Staff authentication is currently unavailable"))
                .thenThrow(new IllegalStateException("private upstream details"));
        mvc.perform(get(REPORT).cookie(access())).andExpect(status().isUnauthorized());
        mvc.perform(get(REPORT).cookie(access())).andExpect(status().isServiceUnavailable());
        mvc.perform(get(REPORT).cookie(access())).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Staff authentication is currently unavailable"));
    }

    @Test
    void explicitGuestRequestsAndGuestOrServiceOnlyRoutesDoNotUseStaffCookies() throws Exception {
        String guestToken = "g".repeat(43);
        when(qr.validateGuestSession(guestToken)).thenReturn(Optional.of(new GuestSessionContext(
                TENANT, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), GuestSessionContext.SessionState.ACTIVE)));
        mvc.perform(get("/api/v1/orders").cookie(access(), new Cookie("restofy_guest", guestToken)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/carts/current").cookie(access())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/internal/table-sessions/" + UUID.randomUUID() + "/order-state").cookie(access()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(auth);
    }

    @Test
    void aGuestCookieOnlyWinsOnGuestRoutesAndNeverBlocksAStaffOnlyRoute() throws Exception {
        String guestToken = "h".repeat(43);
        when(qr.validateGuestSession(guestToken)).thenReturn(Optional.of(new GuestSessionContext(
                TENANT, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), GuestSessionContext.SessionState.ACTIVE)));
        // a stale staff cookie next to a valid guest cookie neither rejects the guest nor reaches the Auth Service
        mvc.perform(get("/api/v1/orders").cookie(new Cookie("restofy_access", "stale-staff-cookie"),
                new Cookie("restofy_guest", guestToken))).andExpect(status().isOk());
        verifyNoInteractions(auth);
        // the same two cookies on a staff-only route: the guest cookie is ignored and the staff session decides
        when(auth.resolveStaff(ACCESS)).thenReturn(Optional.of(staff(StaffRole.ADMIN)));
        mvc.perform(get(REPORT).cookie(access(), new Cookie("restofy_guest", guestToken))).andExpect(status().isOk());
    }

    @Test
    void aGuestCanCancelTheirOrderWhenTheBrowserAlsoSendsAStaffCookie() throws Exception {
        var guest = new GuestSessionContext(TENANT, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                GuestSessionContext.SessionState.ACTIVE);
        when(qr.validateGuestSession(GUEST_TOKEN)).thenReturn(Optional.of(guest));
        var source = new Order.Source(guest.restaurantId(), UUID.randomUUID(), guest.tableId(), guest.tableSessionId(),
                guest.guestSessionId());
        Instant now = Instant.now();
        var order = new Order(UUID.randomUUID(), "ORD-000002", source, "TRY",
                new PriceCalculator.OrderAmounts(100, 0, 100), null, now);
        order.addItem(new OrderItem(order, 1, 1,
                new OrderItem.Snapshot(UUID.randomUUID(), "Tea", "TRY", 0,
                        new PriceCalculator.LinePrice(100, 100, 100L, 0L), null), null, now));
        tx.executeWithoutResult(status -> em.persist(order));

        mvc.perform(guestCancellation(order.getId()).header("X-Guest-CSRF", GUEST_CSRF))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.requestedBy.type").value("GUEST"));
        verify(qr).validateGuestSession(GUEST_TOKEN);
        verifyNoInteractions(auth);
    }

    @Test
    void guestCancellationWithBothCookiesStillRequiresGuestCsrfProof() throws Exception {
        mvc.perform(guestCancellation(UUID.randomUUID()).cookie(proof()).header(CSRF_HEADER, PROOF))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Guest CSRF proof or Origin is invalid"));
        verifyNoInteractions(auth, qr);
    }

    private static MockHttpServletRequestBuilder guestCancellation(UUID orderId) {
        return post("/api/v1/orders/{orderId}/cancellations", orderId)
                .cookie(access(), new Cookie("restofy_guest", GUEST_TOKEN), new Cookie("restofy_guest_csrf", GUEST_CSRF))
                .header(HttpHeaders.ORIGIN, ORIGIN).header("Idempotency-Key", "guest-cancellation-1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"cancelAll\":true}");
    }

    private static MockHttpServletRequestBuilder fulfillment() {
        return patch("/api/v1/orders/" + UUID.randomUUID() + "/fulfillment-status").cookie(access())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SERVED\"}");
    }

    private static MockHttpServletRequestBuilder preflight(String path, String origin) {
        return options(path).header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, CSRF_HEADER + ", Content-Type");
    }

    private static Cookie access() {
        return new Cookie("restofy_access", ACCESS);
    }

    private static Cookie proof() {
        return new Cookie("restofy_csrf", PROOF);
    }

    private static String proofValue(Instant expiresAt) {
        return expiresAt.getEpochSecond() + "." + "a".repeat(43) + "." + "b".repeat(43);
    }

    private static StaffActor staff(StaffRole role) {
        return new StaffActor(USER, TENANT, role);
    }
}

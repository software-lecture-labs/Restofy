package com.restofy.order.api.controller;

import com.restofy.order.application.port.AuthContextPort;
import com.restofy.order.application.port.GuestSessionContext;
import com.restofy.order.application.port.QrSessionPort;
import com.restofy.order.application.port.StaffActor;
import com.restofy.order.domain.entity.Order;
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

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
    private static final String PROOF = "1795080000." + "a".repeat(43) + "." + "b".repeat(43);
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
        when(qr.validateGuestSession("guest-token")).thenReturn(Optional.of(new GuestSessionContext(
                TENANT, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), GuestSessionContext.SessionState.ACTIVE)));
        mvc.perform(get("/api/v1/orders").cookie(access()).header(HttpHeaders.AUTHORIZATION, "Bearer guest-token"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/carts/current").cookie(access())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/internal/table-sessions/" + UUID.randomUUID() + "/order-state").cookie(access()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(auth);
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

    private static StaffActor staff(StaffRole role) {
        return new StaffActor(USER, TENANT, role);
    }
}

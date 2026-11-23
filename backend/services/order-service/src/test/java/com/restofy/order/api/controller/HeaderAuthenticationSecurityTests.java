package com.restofy.order.api.controller;

import com.restofy.order.infrastructure.client.mock.DevFixtures;
import com.restofy.order.infrastructure.security.GuestAuthentication;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

/** Authentication and browser proof remain separate for cookie-backed guest requests. */
class HeaderAuthenticationSecurityTests extends AbstractCartApiTest {

    @Test
    void unrelatedCookiesCannotAuthenticateACartMutation() throws Exception {
        mvc.perform(post(CART + "/items")
                        .cookie(new Cookie("Authorization", guest(DevFixtures.TOKEN_A1)),
                                new Cookie("guest_token", DevFixtures.TOKEN_A1),
                                new Cookie("staff_token", DevFixtures.STAFF_ADMIN_A))
                        .contentType("application/json").content(toJson(product(DevFixtures.SALAD, 1))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        assertEquals(0, count("cart_items"));
    }

    @Test
    void legacyBearerAndDuplicateGuestCookiesCannotAuthenticate() throws Exception {
        String token = DevFixtures.TOKEN_A1;
        mvc.perform(get(CART).with(guestCookie(token)).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(CART).cookie(new Cookie("restofy_guest", token),
                        new Cookie("restofy_guest", DevFixtures.TOKEN_A2)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(CART).with(guestCookie("x".repeat(44))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(CART).with(guestCookie("z".repeat(43))))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing origin", "wrong origin", "duplicate origin", "missing proof",
            "wrong proof", "duplicate proof", "duplicate cookie"})
    void guestWritesRequireExactlyOneValidCsrfProofAndTrustedOrigin(String failure) throws Exception {
        var request = post(CART + "/items").cookie(new Cookie("restofy_guest", DevFixtures.TOKEN_A1))
                .contentType("application/json").content(toJson(product(DevFixtures.SALAD, 1)));
        switch (failure) {
            case "missing origin" -> { /* Leave the Origin header absent. */ }
            case "wrong origin" -> request.header("Origin", "https://other.example");
            case "duplicate origin" -> request.header("Origin", GUEST_ORIGIN, GUEST_ORIGIN);
            default -> request.header("Origin", GUEST_ORIGIN);
        }
        request.cookie(new Cookie("restofy_guest_csrf", GUEST_CSRF));
        switch (failure) {
            case "missing proof" -> { /* Leave the CSRF header absent. */ }
            case "wrong proof" -> request.header("X-Guest-CSRF", "b".repeat(43));
            case "duplicate proof" -> request.header("X-Guest-CSRF", GUEST_CSRF, GUEST_CSRF);
            case "duplicate cookie" -> request.cookie(new Cookie("restofy_guest_csrf", "b".repeat(43)))
                    .header("X-Guest-CSRF", GUEST_CSRF);
            default -> request.header("X-Guest-CSRF", GUEST_CSRF);
        }
        mvc.perform(request).andExpect(status().isForbidden());
        assertEquals(0, count("cart_items"));
    }

    @Test
    void guestCookieWithBrowserProofCanMutateAndReadTheCart() throws Exception {
        addItem(DevFixtures.TOKEN_A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        mvc.perform(get(CART).cookie(new Cookie("restofy_guest", DevFixtures.TOKEN_A1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void safeHeadRequiresGuestAuthenticationButNoCsrfProof() throws Exception {
        mvc.perform(head(CART).cookie(new Cookie("restofy_guest", DevFixtures.TOKEN_A1)))
                .andExpect(status().isOk());
        mvc.perform(head("/api/v1/orders").cookie(new Cookie("restofy_guest", DevFixtures.TOKEN_A1)))
                .andExpect(status().isOk());
        mvc.perform(head(CART)).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "GuestSession realm=\"order\""));
    }

    @Test
    void rejectsAmbiguousAuthorizationBeforeSelectingGuestStaffOrService() throws Exception {
        String staff = "Staff " + DevFixtures.STAFF_ADMIN_A;
        String service = "Service unknown";
        mvc.perform(get(CART).with(guestCookie(DevFixtures.TOKEN_A1)).header("Authorization", staff, service))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/order-cancellations").header("Authorization", staff + ", " + service))
                .andExpect(status().isBadRequest());
        var internal = "/api/v1/internal/table-sessions/" + DevFixtures.TABLE_SESSION_A1 + "/order-state";
        mvc.perform(get(internal).header("Authorization", service, staff)).andExpect(status().isBadRequest());
        mvc.perform(get(internal)).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Service realm=\"order\""));
        mvc.perform(get("/api/v1/orders")).andExpect(status().isUnauthorized())
                .andExpect(header().stringValues("WWW-Authenticate", "GuestSession realm=\"order\"",
                        "Staff realm=\"order\""));
    }

    @Test
    void anExistingHttpSessionCannotAuthenticateACartMutation() throws Exception {
        var guestContext = qr.validateGuestSession(DevFixtures.TOKEN_A1).orElseThrow();
        var session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT",
                new SecurityContextImpl(new GuestAuthentication(guestContext)));
        mvc.perform(post(CART + "/items").session(session)
                        .contentType("application/json").content(toJson(product(DevFixtures.SALAD, 1))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        assertEquals(0, count("cart_items"));
    }

    @Test
    void basicAuthenticationCannotAuthenticateACartMutation() throws Exception {
        mvc.perform(post(CART + "/items").header("Authorization", "Basic dXNlcjpwYXNz")
                        .contentType("application/json").content(toJson(product(DevFixtures.SALAD, 1))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        assertEquals(0, count("cart_items"));
    }
}

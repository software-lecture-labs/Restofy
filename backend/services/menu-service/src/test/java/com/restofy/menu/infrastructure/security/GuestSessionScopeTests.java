package com.restofy.menu.infrastructure.security;

import com.restofy.menu.application.security.GuestSessionVerification;
import com.restofy.menu.domain.exception.PublicMenuUnavailableException;
import com.restofy.menu.support.ScriptedGuestSessionVerifier;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The web-layer half of the public restaurant scope without Spring or a database: when QR is asked, what a verdict
 * binds to the request, and when the production resolver trusts a reference. The verifier is scripted; the cookie,
 * interceptor and resolver are the real classes.
 */
class GuestSessionScopeTests {

    private static final String TOKEN = "A".repeat(43);
    private static final UUID ALPHA = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID BETA = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    private final ScriptedGuestSessionVerifier verifier = new ScriptedGuestSessionVerifier();
    private final PublicGuestScopeInterceptor interceptor = new PublicGuestScopeInterceptor(verifier);
    private final GuestSessionRestaurantScopeResolver resolver = new GuestSessionRestaurantScopeResolver();

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private MockHttpServletRequest request(String reference, Cookie... cookies) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/menu/public/restaurants/x/products");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("restaurantRef", reference));
        if (cookies.length > 0) {
            request.setCookies(cookies);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    private void run(MockHttpServletRequest request) {
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    private static Cookie guest(String value) {
        return new Cookie(PublicGuestCookie.NAME, value);
    }

    @Test
    void aVerifiedGuestMakesOnlyItsOwnRestaurantTrusted() {
        verifier.answer(TOKEN, new GuestSessionVerification.Verified(ALPHA));
        run(request(ALPHA.toString(), guest(TOKEN)));
        assertEquals(Optional.of(ALPHA), resolver.resolve(ALPHA.toString()));
        assertEquals(Optional.of(ALPHA), resolver.resolve(ALPHA.toString().toUpperCase()));
        assertEquals(Optional.empty(), resolver.resolve(BETA.toString()));
        assertEquals(Optional.empty(), resolver.resolve("alpha-menu"));
        assertEquals(Optional.empty(), resolver.resolve(null));
        assertEquals(Optional.empty(), resolver.resolve(""));
    }

    @Test
    void theVerifiersAnswerIsTheOnlySourceOfTheRestaurant() {
        verifier.answer(TOKEN, new GuestSessionVerification.Verified(BETA));
        run(request(ALPHA.toString(), guest(TOKEN)));
        assertEquals(Optional.empty(), resolver.resolve(ALPHA.toString()), "the URL claimed alpha, QR vouched for beta");
        assertEquals(Optional.of(BETA), resolver.resolve(BETA.toString()));
    }

    @Test
    void withoutAVerdictNothingIsTrustedNotEvenARealRestaurantId() {
        assertEquals(Optional.empty(), resolver.resolve(ALPHA.toString()), "no request at all");
        run(request(ALPHA.toString()));
        assertEquals(Optional.empty(), resolver.resolve(ALPHA.toString()), "a request without a credential");
        assertEquals(0, verifier.asked().size());
    }

    @Test
    void aRefusedCredentialBindsNothing() {
        run(request(ALPHA.toString(), guest(TOKEN)));
        assertEquals(1, verifier.asked().size());
        assertEquals(Optional.empty(), resolver.resolve(ALPHA.toString()));
    }

    @Test
    void qrIsAskedOnlyForAWellFormedCredentialAndACanonicalRestaurantReference() {
        run(request(ALPHA.toString()));
        run(request(ALPHA.toString(), guest("short")));
        run(request(ALPHA.toString(), guest(TOKEN), guest(TOKEN)));
        run(request("alpha-menu", guest(TOKEN)));
        run(request("x".repeat(500), guest(TOKEN)));
        run(request("{" + ALPHA + "}", guest(TOKEN)));
        run(request(ALPHA.toString(), new Cookie("restofy_guest_csrf", TOKEN), new Cookie("restofy_access", TOKEN),
                new Cookie("guest", TOKEN)));
        assertEquals(0, verifier.asked().size());
        run(request(ALPHA.toString(), guest(TOKEN)));
        assertEquals(java.util.List.of(TOKEN), verifier.asked());
    }

    @Test
    void aRestaurantIdInTheCookieIsNotACredential() {
        run(request(ALPHA.toString(), guest(ALPHA.toString())));
        assertEquals(0, verifier.asked().size());
        assertEquals(Optional.empty(), resolver.resolve(ALPHA.toString()));
    }

    @Test
    void noDecisionIsUnavailableNeverASuccessAndNeverARefusal() {
        verifier.answer(TOKEN, new GuestSessionVerification.Unavailable());
        assertThrows(PublicMenuUnavailableException.class, () -> run(request(ALPHA.toString(), guest(TOKEN))));

        verifier.reset();
        verifier.fail();
        assertThrows(PublicMenuUnavailableException.class, () -> run(request(ALPHA.toString(), guest(TOKEN))));

        verifier.reset();
        verifier.answerNothing();
        assertThrows(PublicMenuUnavailableException.class, () -> run(request(ALPHA.toString(), guest(TOKEN))));
        assertEquals(Optional.empty(), resolver.resolve(ALPHA.toString()));
    }

    @Test
    void aFailingVerifierDoesNotCarryTheCredentialIntoTheException() {
        verifier.fail();
        PublicMenuUnavailableException e = assertThrows(PublicMenuUnavailableException.class,
                () -> run(request(ALPHA.toString(), guest(TOKEN))));
        assertEquals("The menu is temporarily unavailable.", e.getMessage());
        assertEquals(null, e.getCause());
    }
}

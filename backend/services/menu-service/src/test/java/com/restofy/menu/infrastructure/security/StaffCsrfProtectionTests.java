package com.restofy.menu.infrastructure.security;

import com.restofy.menu.support.StaffRequests;
import com.restofy.menu.support.TestStaffTokens;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The browser proof of a staff mutation, piece by piece. The two halves of the double submit (the cookie Auth issued
 * and the header the UI echoes) and the Origin/content-type rules each refuse on their own; the repository never
 * writes a cookie of its own.
 */
class StaffCsrfProtectionTests {

    private static final String ORIGIN = TestStaffTokens.ORIGIN;

    private final StaffCsrfProtection protection = new StaffCsrfProtection(Set.of(ORIGIN), Clock.systemUTC());

    private static MockHttpServletRequest mutation() {
        MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/api/v1/menu/products/x");
        request.setContentType("application/json");
        return request;
    }

    private MockHttpServletRequest withProof(String proof) {
        MockHttpServletRequest request = mutation();
        request.setCookies(new Cookie(StaffRequests.CSRF_COOKIE, proof));
        request.addHeader(StaffRequests.CSRF_HEADER, proof);
        request.addHeader("Origin", ORIGIN);
        return request;
    }

    private CsrfToken loaded(MockHttpServletRequest request) {
        return protection.loadToken(request);
    }

    private String resolved(MockHttpServletRequest request) {
        return protection.resolveCsrfTokenValue(request, protection.generateToken(request));
    }

    @Test
    void aCompleteProofIsLoadedAndResolvedToTheSameValue() {
        String proof = StaffRequests.proof();
        MockHttpServletRequest request = withProof(proof);
        assertEquals(proof, loaded(request).getToken());
        assertEquals(proof, resolved(request));
        assertEquals("X-CSRF-TOKEN", loaded(request).getHeaderName());
    }

    @Test
    void noCookieIsNoToken() {
        assertNull(loaded(mutation()));
        MockHttpServletRequest request = mutation();
        request.setCookies(new Cookie("other", "x"));
        assertNull(loaded(request));
    }

    @Test
    void aRepeatedCookieIsNoProof() {
        MockHttpServletRequest request = mutation();
        request.setCookies(new Cookie(StaffRequests.CSRF_COOKIE, StaffRequests.proof()), new Cookie(StaffRequests.CSRF_COOKIE,
                StaffRequests.proof()));
        assertNull(loaded(request));
    }

    @Test
    void anExpiredOrMalformedCookieIsNoProof() {
        assertNull(loaded(withProof(StaffRequests.proofExpiringAt(Instant.now().minusSeconds(1)))));
        for (String bad : new String[] {"", "abc", "1.2.3", "9999999999.short.short", "x." + "A".repeat(43) + "." + "A".repeat(43),
                StaffRequests.proof() + "x", "99999999999999." + "A".repeat(43) + "." + "A".repeat(43)}) {
            MockHttpServletRequest request = mutation();
            request.setCookies(new Cookie(StaffRequests.CSRF_COOKIE, bad));
            assertNull(loaded(request), bad);
        }
    }

    @Test
    void theHeaderMustBeExactlyOneWellFormedProof() {
        String proof = StaffRequests.proof();
        MockHttpServletRequest none = mutation();
        none.setCookies(new Cookie(StaffRequests.CSRF_COOKIE, proof));
        none.addHeader("Origin", ORIGIN);
        assertNull(resolved(none));

        MockHttpServletRequest twice = withProof(proof);
        twice.addHeader(StaffRequests.CSRF_HEADER, proof);
        assertNull(resolved(twice));

        MockHttpServletRequest malformed = mutation();
        malformed.addHeader(StaffRequests.CSRF_HEADER, "not-a-proof");
        malformed.addHeader("Origin", ORIGIN);
        assertNull(resolved(malformed));
    }

    @Test
    void theOriginMustBeExactlyOneAllowlistedValue() {
        String proof = StaffRequests.proof();
        for (String origin : new String[] {"http://evil.example", "null", "http://localhost:5001", "https://localhost:5000",
                "http://localhost:5000/", "HTTP://LOCALHOST:5000", ""}) {
            MockHttpServletRequest request = mutation();
            request.addHeader(StaffRequests.CSRF_HEADER, proof);
            request.addHeader("Origin", origin);
            assertNull(resolved(request), origin);
        }
        MockHttpServletRequest missing = mutation();
        missing.addHeader(StaffRequests.CSRF_HEADER, proof);
        assertNull(resolved(missing));

        MockHttpServletRequest twice = withProof(proof);
        twice.addHeader("Origin", "http://evil.example");
        assertNull(resolved(twice), "a second Origin, even a foreign one, is ambiguous");
    }

    @Test
    void anEmptyAllowlistRefusesEveryMutation() {
        StaffCsrfProtection nothingAllowed = new StaffCsrfProtection(Set.of(), Clock.systemUTC());
        MockHttpServletRequest request = withProof(StaffRequests.proof());
        assertNull(nothingAllowed.resolveCsrfTokenValue(request, nothingAllowed.generateToken(request)));
    }

    @Test
    void aMutationMustBeJsonWhenItHasAContentType() {
        String proof = StaffRequests.proof();
        for (String type : new String[] {"text/plain", "application/x-www-form-urlencoded", "multipart/form-data", "application/xml",
                "garbage", "application/*"}) {
            MockHttpServletRequest request = withProof(proof);
            request.removeHeader("Content-Type");
            request.addHeader("Content-Type", type);
            assertNull(resolved(request), type);
        }
        MockHttpServletRequest charset = withProof(proof);
        charset.removeHeader("Content-Type");
        charset.addHeader("Content-Type", "application/json;charset=UTF-8");
        assertEquals(proof, resolved(charset));
    }

    @Test
    void theGeneratedFallbackTokenNeverMatchesAHeaderAndIsNeverWritten() {
        MockHttpServletRequest request = mutation();
        CsrfToken fallback = protection.generateToken(request);
        assertNotNull(fallback.getToken());
        assertTrue(fallback.getToken().length() > 0);
        assertTrue(!fallback.getToken().matches("\\d{1,12}\\.[A-Za-z0-9_-]{43}\\.[A-Za-z0-9_-]{43}"),
                "a client can never produce a header equal to a generated fallback");
        MockHttpServletResponse response = new MockHttpServletResponse();
        protection.saveToken(fallback, request, response);
        protection.saveToken(null, request, response);
        assertNull(response.getHeader("Set-Cookie"));
        assertEquals(0, response.getCookies().length);
    }
}

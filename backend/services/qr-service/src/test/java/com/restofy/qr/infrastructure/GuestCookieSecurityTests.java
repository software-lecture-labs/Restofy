package com.restofy.qr.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.restofy.qr.domain.exception.InvalidGuestSessionException;
import com.restofy.qr.infrastructure.security.Q5SecuritySettings;
import com.restofy.qr.infrastructure.security.guest.GuestCookies;
import com.restofy.qr.infrastructure.security.guest.GuestRequestFilter;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Pure unit tests: cookie attributes (K4), per-request cookie parsing (K5) and Origin/CSRF proof (K6). */
class GuestCookieSecurityTests {
    private static final String ORIGIN = "https://restofy.example";
    private static final String TOKEN = "A".repeat(43);
    private static final String CSRF = "B".repeat(43);
    private static final String ACTIVITY = "/api/v1/qr/guest-sessions/activity";

    private static GuestCookies cookies(boolean development) {
        var settings = mock(Q5SecuritySettings.class);
        when(settings.development()).thenReturn(development);
        return new GuestCookies(settings);
    }

    private static GuestRequestFilter filter() {
        var settings = mock(Q5SecuritySettings.class);
        when(settings.origins()).thenReturn(List.of(ORIGIN));
        return new GuestRequestFilter(settings);
    }

    private static MockHttpServletRequest activity() {
        var request = new MockHttpServletRequest("POST", ACTIVITY);
        request.setRequestURI(ACTIVITY);
        request.addHeader("Origin", ORIGIN);
        request.setCookies(new Cookie("restofy_guest", TOKEN), new Cookie("restofy_guest_csrf", CSRF));
        request.addHeader("X-Guest-CSRF", CSRF);
        return request;
    }

    private static MockHttpServletRequest open(String path) {
        var request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        request.addHeader("Origin", ORIGIN);
        request.setContentType("application/json");
        return request;
    }

    private static int run(MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter().doFilter(request, response, chain);
        return chain.getRequest() != null ? 200 : response.getStatus();
    }

    @Test
    void productionCookiesAreHttpOnlySecureStrictHostOnlyAndScopedToApi() {
        var response = new MockHttpServletResponse();
        cookies(false).issue(response, TOKEN);
        var set = response.getHeaders("Set-Cookie");
        assertThat(set).hasSize(2);
        assertThat(set.get(0)).startsWith("restofy_guest=" + TOKEN).contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/v1", "Max-Age=")
                .doesNotContain("Domain");
        assertThat(set.get(1)).startsWith("restofy_guest_csrf=").contains("Secure", "SameSite=Strict", "Path=/api/v1").doesNotContain("HttpOnly");
    }

    @Test
    void secureIsOnlyDroppedInTheExplicitDevelopmentProfile() {
        var response = new MockHttpServletResponse();
        cookies(true).issue(response, TOKEN);
        assertThat(response.getHeaders("Set-Cookie")).allSatisfy(cookie -> assertThat(cookie).doesNotContain("Secure"));
    }

    @Test
    void clearUsesTheSameNamePathAndAttributesWithZeroLifetime() {
        var response = new MockHttpServletResponse();
        cookies(false).clear(response);
        assertThat(response.getHeaders("Set-Cookie")).hasSize(2).allSatisfy(cookie ->
                assertThat(cookie).contains("Max-Age=0", "Path=/api/v1", "SameSite=Strict", "Secure"));
    }

    @Test
    void duplicateMissingOrMalformedCredentialCookiesAreInvalidGuests() {
        var cookies = cookies(false);
        var duplicate = new MockHttpServletRequest();
        duplicate.setCookies(new Cookie("restofy_guest", TOKEN), new Cookie("restofy_guest", TOKEN));
        var malformed = new MockHttpServletRequest();
        malformed.setCookies(new Cookie("restofy_guest", "short"));
        for (var request : List.of(duplicate, malformed, new MockHttpServletRequest())) {
            assertThatThrownBy(() -> cookies.guestToken(request)).isInstanceOf(InvalidGuestSessionException.class);
        }
        var valid = new MockHttpServletRequest();
        valid.setCookies(new Cookie("restofy_guest", TOKEN));
        assertThat(cookies.guestToken(valid)).isEqualTo(TOKEN);
    }

    @Test
    void nonGuestRequestsPassThroughUntouched() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/qr/tables");
        request.setRequestURI("/api/v1/qr/tables");
        assertThat(run(request)).isEqualTo(200);
    }

    @Test
    void validActivityProofPassesTheFilter() throws Exception {
        assertThat(run(activity())).isEqualTo(200);
    }

    @Test
    void activityNeedsExactlyOneAllowlistedOrigin() throws Exception {
        var missing = activity(); missing.removeHeader("Origin");
        var foreign = activity(); foreign.removeHeader("Origin"); foreign.addHeader("Origin", "https://evil.example");
        var twice = activity(); twice.addHeader("Origin", ORIGIN);
        for (var request : List.of(missing, foreign, twice)) { assertThat(run(request)).isEqualTo(403); }
    }

    @Test
    void activityRejectsMissingMismatchedOrRepeatedCsrfProof() throws Exception {
        var noHeader = activity(); noHeader.removeHeader("X-Guest-CSRF");
        var wrong = activity(); wrong.removeHeader("X-Guest-CSRF"); wrong.addHeader("X-Guest-CSRF", "C".repeat(43));
        var noCookie = activity(); noCookie.setCookies(new Cookie("restofy_guest", TOKEN));
        var twice = activity(); twice.addHeader("X-Guest-CSRF", CSRF);
        for (var request : List.of(noHeader, wrong, noCookie, twice)) { assertThat(run(request)).isEqualTo(403); }
    }

    @Test
    void activityWithoutAValidCredentialCookieIsUnauthorizedAndStaffCookiesDoNotSubstitute() throws Exception {
        var none = activity(); none.setCookies(new Cookie("restofy_guest_csrf", CSRF));
        var staff = activity(); staff.setCookies(new Cookie("restofy_access", TOKEN), new Cookie("restofy_guest_csrf", CSRF));
        var bearer = activity(); bearer.addHeader("Authorization", "Bearer " + TOKEN);
        assertThat(run(none)).isEqualTo(401);
        assertThat(run(staff)).isEqualTo(401);
        assertThat(run(bearer)).isEqualTo(403);
    }

    @Test
    void validateAndOpenNeedAnAllowlistedOriginAndJsonAndNeverAnAuthorizationHeader() throws Exception {
        for (String path : List.of("/api/v1/qr/validate", "/api/v1/qr/table-sessions/open")) {
            assertThat(run(open(path))).isEqualTo(200);
            var foreign = open(path); foreign.removeHeader("Origin"); foreign.addHeader("Origin", "https://evil.example");
            var form = open(path); form.setContentType("application/x-www-form-urlencoded");
            var noType = open(path); noType.setContentType(null);
            var bearer = open(path); bearer.addHeader("Authorization", "Bearer x");
            for (var request : List.of(foreign, form, noType, bearer)) { assertThat(run(request)).isEqualTo(403); }
        }
    }
}

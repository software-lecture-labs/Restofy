package com.restofy.qr.infrastructure.security.guest;

import com.restofy.qr.domain.exception.InvalidGuestSessionException;
import com.restofy.qr.infrastructure.security.Q5SecuritySettings;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Browser transport of the guest credential (ADR-QR-002 K3/K4/K6). Cookie lifetime is not session validity:
 * QR re-verifies the guest on every use, so a leftover cookie never grants access to an unusable session.
 */
@Component
public class GuestCookies {
    public static final String TOKEN = "restofy_guest";
    public static final String CSRF = "restofy_guest_csrf";
    public static final String CSRF_HEADER = "X-Guest-CSRF";
    public static final String PATTERN = "[A-Za-z0-9_-]{43}";
    private static final String PATH = "/api/v1";
    // Longer than the idle window so an order-protected guest keeps its credential; refreshed on every activity.
    private static final Duration LIFETIME = Duration.ofHours(12);
    private final SecureRandom random = new SecureRandom();
    private final boolean secure;

    public GuestCookies(Q5SecuritySettings settings) {
        // Production is always Secure; plain HTTP is only possible in the explicit dev/local/test profile.
        this.secure = !settings.development();
    }

    /**
     * New guest: HttpOnly credential plus the CSRF proof. The proof is returned for the response body because the
     * frontend page cannot read a cookie scoped to Path=/api/v1: the page itself lives under other paths.
     */
    public String issue(HttpServletResponse response, String guestToken) {
        String csrf = newCsrf();
        add(response, build(TOKEN, guestToken, true, LIFETIME));
        add(response, build(CSRF, csrf, false, LIFETIME));
        return csrf;
    }

    /** The browser's current CSRF proof, or a fresh one set as a cookie when it is missing or malformed. */
    public String csrfFor(HttpServletRequest request, HttpServletResponse response) {
        String current = single(request, CSRF);
        if (current != null && current.matches(PATTERN)) { return current; }
        String csrf = newCsrf();
        add(response, build(CSRF, csrf, false, LIFETIME));
        return csrf;
    }

    /** Sliding refresh for a verified guest; keeps the browser's existing CSRF proof. */
    public void refresh(HttpServletResponse response, String guestToken, String csrf) {
        add(response, build(TOKEN, guestToken, true, LIFETIME));
        add(response, build(CSRF, csrf != null && csrf.matches(PATTERN) ? csrf : newCsrf(), false, LIFETIME));
    }

    /** Same name, Path and attributes as issue(), otherwise browsers would keep the old cookie. */
    public void clear(HttpServletResponse response) {
        add(response, build(TOKEN, "", true, Duration.ZERO));
        add(response, build(CSRF, "", false, Duration.ZERO));
    }

    /** Exactly one well-formed credential cookie; anything else is treated as an invalid guest. */
    public String guestToken(HttpServletRequest request) {
        String value = single(request, TOKEN);
        if (value == null || !value.matches(PATTERN)) { throw new InvalidGuestSessionException(); }
        return value;
    }

    public String csrfProof(HttpServletRequest request) {
        return single(request, CSRF);
    }

    static String single(HttpServletRequest request, String name) {
        String value = null;
        int count = 0;
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (!name.equals(cookie.getName())) { continue; }
                if (++count > 1) { throw new InvalidGuestSessionException(); }
                value = cookie.getValue();
            }
        }
        return value;
    }

    private ResponseCookie build(String name, String value, boolean httpOnly, Duration maxAge) {
        // No Domain attribute: host-only, so the cookie is never shared with sibling subdomains.
        return ResponseCookie.from(name, value).httpOnly(httpOnly).secure(secure).sameSite("Strict")
                .path(PATH).maxAge(maxAge).build();
    }

    private String newCsrf() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void add(HttpServletResponse response, ResponseCookie cookie) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}

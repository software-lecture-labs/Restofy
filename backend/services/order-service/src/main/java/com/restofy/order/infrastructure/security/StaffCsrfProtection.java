package com.restofy.order.infrastructure.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import java.time.Clock;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Supplies Auth's double-submit proof to Spring Security without writing an Auth-owned cookie. */
final class StaffCsrfProtection extends CsrfTokenRequestAttributeHandler implements CsrfTokenRepository {
    static final String ACCESS_COOKIE = "restofy_access";
    static final String CSRF_HEADER = "X-CSRF-TOKEN";
    private static final String CSRF_COOKIE = "restofy_csrf";
    private static final Pattern PROOF = Pattern.compile("(\\d{1,12})\\.[A-Za-z0-9_-]{43}\\.[A-Za-z0-9_-]{43}");
    private final Set<String> origins;
    private final Clock clock;

    StaffCsrfProtection(Set<String> origins, Clock clock) {
        this.origins = origins;
        this.clock = clock;
    }

    static boolean appliesTo(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        return CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request) && request.getHeader(HttpHeaders.AUTHORIZATION) == null
                && !GuestSessionAuthenticationFilter.hasGuestCookieOnGuestRoute(request)
                && cookies != null && Arrays.stream(cookies).anyMatch(cookie -> ACCESS_COOKIE.equals(cookie.getName()));
    }

    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        return token(UUID.randomUUID().toString());
    }

    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        // Auth owns the CSRF cookie. The generated fallback is never sent to the client.
    }

    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        var cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        var proofs = Arrays.stream(cookies).filter(cookie -> CSRF_COOKIE.equals(cookie.getName()))
                .map(Cookie::getValue).toList();
        if (proofs.size() != 1) {
            return null;
        }
        String proof = proofs.getFirst();
        var fields = PROOF.matcher(proof);
        if (!fields.matches() || Long.parseLong(fields.group(1)) <= clock.instant().getEpochSecond()) {
            return null;
        }
        return token(proof);
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken token) {
        var origin = request.getHeaders(HttpHeaders.ORIGIN);
        if (!origin.hasMoreElements() || !origins.contains(origin.nextElement()) || origin.hasMoreElements()) {
            return null;
        }
        var proofs = request.getHeaders(CSRF_HEADER);
        if (!proofs.hasMoreElements()) {
            return null;
        }
        String header = proofs.nextElement();
        return !proofs.hasMoreElements() && PROOF.matcher(header).matches() ? header : null;
    }

    private static CsrfToken token(String value) {
        return new DefaultCsrfToken(CSRF_HEADER, "_csrf", value);
    }
}

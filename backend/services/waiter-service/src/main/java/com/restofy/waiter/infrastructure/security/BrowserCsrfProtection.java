package com.restofy.waiter.infrastructure.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import java.time.Clock;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * CSRF and Origin proof for every browser mutation of this service, as one Spring Security token repository and
 * request handler. Both credentials a browser can attach on its own need it: the staff cookie of Auth and the
 * guest cookie of QR. The proof is a double submit: a script-readable cookie that Auth or QR issued must come back
 * as a request header with the same value, and the request must carry exactly one {@code Origin} that is on the
 * allowlist. Another site can neither read that cookie nor send that header, and cannot forge the Origin.
 *
 * <ul>
 *   <li><b>Guest call</b> ({@code POST /api/v1/waiters/calls}): cookie {@value #GUEST_COOKIE}, header
 *       {@value #GUEST_HEADER} (QR's ADR-QR-002 K6 contract).</li>
 *   <li><b>Every other mutation</b> (staff): cookie {@value #STAFF_COOKIE} of Auth, which carries its own
 *       expiry, and header {@value #STAFF_HEADER}. Auth's signature stays with Auth; Waiter holds no secret.</li>
 * </ul>
 *
 * Spring's {@code CsrfFilter} stays active for all of them with its default method rules: a mutation whose proof
 * is missing, doubled, expired or different fails with 403 before it reaches authentication. Nothing here turns
 * CSRF protection off, not even for the guest call. The repository writes nothing: both cookies belong to Auth
 * and QR.
 */
final class BrowserCsrfProtection extends CsrfTokenRequestAttributeHandler implements CsrfTokenRepository {

    static final String STAFF_COOKIE = "restofy_csrf";
    static final String STAFF_HEADER = "X-CSRF-TOKEN";
    static final String GUEST_COOKIE = "restofy_guest_csrf";
    static final String GUEST_HEADER = "X-Guest-CSRF";

    private static final String PARAMETER = "_csrf";
    private static final Pattern STAFF_PROOF = Pattern.compile("(\\d{1,12})\\.[A-Za-z0-9_-]{43}\\.[A-Za-z0-9_-]{43}");
    private static final Pattern GUEST_PROOF = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final Set<String> origins;
    private final Clock clock;

    BrowserCsrfProtection(Set<String> origins, Clock clock) {
        this.origins = origins;
        this.clock = clock;
    }

    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        return token(request, UUID.randomUUID().toString());
    }

    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        // Auth and QR own the cookies. The generated fallback is never sent to the client.
    }

    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        boolean guest = GuestSessionAuthenticationFilter.isGuestCallRequest(request);
        String proof = singleCookie(request, guest ? GUEST_COOKIE : STAFF_COOKIE);
        if (proof == null) return null;
        if (guest ? !GUEST_PROOF.matcher(proof).matches() : !validStaffProof(proof)) return null;
        return token(request, proof);
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken token) {
        List<String> origin = Collections.list(request.getHeaders(HttpHeaders.ORIGIN));
        if (origin.size() != 1 || !origins.contains(origin.getFirst())) return null;
        List<String> proofs = Collections.list(request.getHeaders(token.getHeaderName()));
        if (proofs.size() != 1) return null;
        String header = proofs.getFirst();
        boolean guest = GuestSessionAuthenticationFilter.isGuestCallRequest(request);
        boolean wellFormed = guest ? GUEST_PROOF.matcher(header).matches() : STAFF_PROOF.matcher(header).matches();
        return wellFormed ? header : null;
    }

    private boolean validStaffProof(String proof) {
        var fields = STAFF_PROOF.matcher(proof);
        return fields.matches() && Long.parseLong(fields.group(1)) > clock.instant().getEpochSecond();
    }

    private static CsrfToken token(HttpServletRequest request, String value) {
        String header = GuestSessionAuthenticationFilter.isGuestCallRequest(request) ? GUEST_HEADER : STAFF_HEADER;
        return new DefaultCsrfToken(header, PARAMETER, value);
    }

    /** Exactly one cookie of that name; a missing or repeated cookie is no proof. */
    private static String singleCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        List<String> values = Arrays.stream(cookies).filter(cookie -> name.equals(cookie.getName()))
                .map(Cookie::getValue).toList();
        return values.size() == 1 ? values.getFirst() : null;
    }
}

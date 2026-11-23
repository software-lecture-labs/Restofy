package com.restofy.menu.infrastructure.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CSRF and Origin proof for every staff mutation, as one Spring Security token repository and request handler. The
 * staff credential is a cookie a browser attaches on its own, so a mutation must also prove it comes from the
 * Restofy UI. The proof is the double submit of Auth's {@code staff-v1} contract: the script-readable cookie
 * {@value #COOKIE} that Auth issued must come back as the header {@value #HEADER} with the same value, and the request
 * must carry exactly one {@code Origin} on the allowlist and, when it has a content type, exactly {@code application/json} (parameters such as the charset are fine). Another site
 * can neither read that cookie nor set that header, and cannot forge the Origin.
 *
 * <p>Spring's {@code CsrfFilter} stays active with its default method rules (GET, HEAD, TRACE and OPTIONS are
 * exempt): a mutation whose proof is missing, doubled, expired, malformed or different is a 403 before it reaches
 * authentication. Nothing here turns CSRF protection off. The repository writes nothing: the cookie belongs to Auth.
 *
 * <p>Auth keeps the HMAC key that binds the proof to a session, so Menu can check the shape and the expiry the proof
 * carries but not its signature; that is why the Origin allowlist is part of the same decision and an empty allowlist
 * refuses every mutation.
 */
final class StaffCsrfProtection extends CsrfTokenRequestAttributeHandler implements CsrfTokenRepository {

    static final String COOKIE = "restofy_csrf";
    static final String HEADER = "X-CSRF-TOKEN";

    private static final String PARAMETER = "_csrf";
    /** {@code expiryEpochSeconds.nonce.signature}, the nonce and the signature being 32 bytes in unpadded base64url. */
    private static final Pattern PROOF = Pattern.compile("(\\d{1,12})\\.[A-Za-z0-9_-]{43}\\.[A-Za-z0-9_-]{43}");

    private final Set<String> origins;
    private final Clock clock;

    StaffCsrfProtection(Set<String> origins, Clock clock) {
        this.origins = origins;
        this.clock = clock;
    }

    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        return new DefaultCsrfToken(HEADER, PARAMETER, UUID.randomUUID().toString());
    }

    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        // Auth owns the cookie. The generated fallback is never sent to the client.
    }

    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        String proof = singleCookie(request);
        return proof != null && liveProof(proof) ? new DefaultCsrfToken(HEADER, PARAMETER, proof) : null;
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken token) {
        List<String> origin = Collections.list(request.getHeaders(HttpHeaders.ORIGIN));
        if (origin.size() != 1 || !origins.contains(origin.getFirst()) || !jsonOrNoBody(request)) {
            return null;
        }
        List<String> proofs = Collections.list(request.getHeaders(token.getHeaderName()));
        return proofs.size() == 1 && PROOF.matcher(proofs.getFirst()).matches() ? proofs.getFirst() : null;
    }

    private boolean liveProof(String proof) {
        Matcher fields = PROOF.matcher(proof);
        return fields.matches() && Long.parseLong(fields.group(1)) > clock.instant().getEpochSecond();
    }

    private static boolean jsonOrNoBody(HttpServletRequest request) {
        List<String> types = Collections.list(request.getHeaders(HttpHeaders.CONTENT_TYPE));
        if (types.isEmpty()) {
            return true;
        }
        try {
            return types.size() == 1 && MediaType.APPLICATION_JSON.equalsTypeAndSubtype(MediaType.parseMediaType(types.getFirst()));
        } catch (InvalidMediaTypeException _) {
            return false;
        }
    }

    /** Exactly one cookie of that name; a missing or repeated cookie is no proof. */
    private static String singleCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        List<String> values = Arrays.stream(cookies).filter(cookie -> COOKIE.equals(cookie.getName()))
                .map(Cookie::getValue).toList();
        return values.size() == 1 ? values.getFirst() : null;
    }
}

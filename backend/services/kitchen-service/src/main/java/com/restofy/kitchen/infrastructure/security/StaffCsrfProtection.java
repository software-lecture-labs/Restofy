package com.restofy.kitchen.infrastructure.security;

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
import java.util.regex.Pattern;

/**
 * The consumer side of Auth's CSRF contract, as one Spring Security token repository and request handler. A
 * mutation passes only with ALL of:
 * <ul>
 *   <li>exactly one {@code Origin} header that is on the allowlist;</li>
 *   <li>exactly one {@code restofy_csrf} cookie in Auth's {@code expiry.nonce.signature} shape, not expired;</li>
 *   <li>exactly one {@code X-CSRF-TOKEN} header with the same value;</li>
 *   <li>if a {@code Content-Type} is sent, JSON.</li>
 * </ul>
 * Spring's {@code CsrfFilter} stays on for every mutation with its default method rules and no exemption.
 *
 * <p>Auth's HMAC over the proof stays with Auth: Kitchen holds no CSRF secret and does not claim to verify the
 * signature. The protection is the Origin allowlist plus the double submit. The repository writes nothing, because
 * the cookie belongs to Auth.
 */
final class StaffCsrfProtection extends CsrfTokenRequestAttributeHandler implements CsrfTokenRepository {

	static final String COOKIE = "restofy_csrf";
	static final String HEADER = "X-CSRF-TOKEN";
	private static final String PARAMETER = "_csrf";
	private static final Pattern PROOF = Pattern.compile("(\\d{1,12})\\.[A-Za-z0-9_-]{43}\\.[A-Za-z0-9_-]{43}");

	private final Set<String> origins;
	private final Clock clock;

	StaffCsrfProtection(List<String> origins, Clock clock) {
		this.origins = Set.copyOf(origins);
		this.clock = clock;
	}

	@Override
	public CsrfToken generateToken(HttpServletRequest request) {
		return token(UUID.randomUUID().toString());
	}

	@Override
	public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
		// Auth owns the cookie. The generated fallback is never sent to the client.
	}

	@Override
	public CsrfToken loadToken(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		List<String> proofs = Arrays.stream(cookies).filter(cookie -> COOKIE.equals(cookie.getName()))
				.map(Cookie::getValue).toList();
		return proofs.size() == 1 && unexpired(proofs.getFirst()) ? token(proofs.getFirst()) : null;
	}

	/** The submitted proof, or {@code null} (which fails the CSRF check) when any other condition is not met. */
	@Override
	public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken token) {
		List<String> origin = Collections.list(request.getHeaders(HttpHeaders.ORIGIN));
		if (origin.size() != 1 || !origins.contains(origin.getFirst()) || !jsonOrNoContentType(request)) {
			return null;
		}
		List<String> proofs = Collections.list(request.getHeaders(HEADER));
		if (proofs.size() != 1 || !PROOF.matcher(proofs.getFirst()).matches()) {
			return null;
		}
		return proofs.getFirst();
	}

	private boolean unexpired(String proof) {
		var fields = PROOF.matcher(proof);
		return fields.matches() && Long.parseLong(fields.group(1)) > clock.instant().getEpochSecond();
	}

	private static boolean jsonOrNoContentType(HttpServletRequest request) {
		List<String> types = Collections.list(request.getHeaders(HttpHeaders.CONTENT_TYPE));
		if (types.isEmpty()) {
			return true;
		}
		try {
			return types.size() == 1
					&& MediaType.APPLICATION_JSON.equalsTypeAndSubtype(MediaType.parseMediaType(types.getFirst()));
		} catch (InvalidMediaTypeException _) {
			return false;
		}
	}

	private static CsrfToken token(String value) {
		return new DefaultCsrfToken(HEADER, PARAMETER, value);
	}
}

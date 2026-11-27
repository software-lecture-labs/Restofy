package com.restofy.kitchen.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Authenticates the one Order event endpoint. In this order: a valid credential (401), HTTPS (403), a request that is
 * the one that was signed (403), a body within the limit (413) that matches the signed digest (403). Nothing is
 * passed on otherwise. HTTPS is what the server itself sees; no forwarded header is trusted. Credentials and bodies
 * are never logged.
 *
 * <p>Deliberately not a bean: a bean filter would also be applied to every other request.
 */
final class OrderEventAuthenticationFilter extends OncePerRequestFilter {

	/** Limit of the signed body in bytes, the same as the Order side applies before sending. */
	static final int MAX_BODY_BYTES = 256 * 1024;

	private static final Logger log = LoggerFactory.getLogger(OrderEventAuthenticationFilter.class);
	private static final String SCHEME = "Service ";

	private final OrderCallerVerifier verifier;

	OrderEventAuthenticationFilter(OrderCallerVerifier verifier) {
		this.verifier = verifier;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Optional<OrderCallerVerifier.VerifiedCredential> credential;
		try {
			credential = verify(request.getHeader(HttpHeaders.AUTHORIZATION));
		} catch (RuntimeException unexpected) {
			// Fail closed, and as a server error so that the caller tries again.
			log.error("Order credential verification failed unexpectedly: {}", unexpected.getClass().getName());
			reject(response, HttpStatus.SERVICE_UNAVAILABLE, "AUTHENTICATION_UNAVAILABLE",
					"The credential cannot be verified right now");
			return;
		}
		if (credential.isEmpty()) {
			response.setHeader(HttpHeaders.WWW_AUTHENTICATE, SCHEME.strip());
			reject(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "A valid Order service credential is required");
			return;
		}
		if (!request.isSecure()) {
			reject(response, HttpStatus.FORBIDDEN, "HTTPS_REQUIRED", "Order events are accepted over HTTPS only");
			return;
		}
		if (!isTheSignedRequest(request)) {
			rejectBinding(response);
			return;
		}
		byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
		if (body.length > MAX_BODY_BYTES) {
			reject(response, HttpStatus.CONTENT_TOO_LARGE, "BODY_TOO_LARGE", "The request body is too large");
			return;
		}
		if (!MessageDigest.isEqual(digest(body), credential.get().bodyDigest().getBytes(StandardCharsets.US_ASCII))) {
			rejectBinding(response);
			return;
		}
		request.setAttribute(VerifiedOrderRequest.ATTRIBUTE,
				new VerifiedOrderRequest(credential.get().restaurantId(), body));
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(OrderCallerVerifier.SUBJECT, null,
				List.of(new SimpleGrantedAuthority(OrderEventSecurityConfiguration.AUTHORITY))));
		SecurityContextHolder.setContext(context);
		chain.doFilter(request, response);
	}

	private Optional<OrderCallerVerifier.VerifiedCredential> verify(String header) {
		if (header == null || !header.startsWith(SCHEME)) {
			return Optional.empty();
		}
		return verifier.verify(header.substring(SCHEME.length()).trim(), OrderEventSecurityConfiguration.METHOD,
				OrderEventSecurityConfiguration.PATH);
	}

	/**
	 * The signature covers method and raw path, so both must be literally what was signed. Cookie and Origin mean a
	 * browser; an encoded body or a second Authorization header would make "what was signed" ambiguous.
	 */
	private static boolean isTheSignedRequest(HttpServletRequest request) {
		return OrderEventSecurityConfiguration.METHOD.equals(request.getMethod())
				&& OrderEventSecurityConfiguration.PATH.equals(request.getRequestURI())
				&& request.getQueryString() == null
				&& request.getHeader(HttpHeaders.ORIGIN) == null
				&& request.getHeader(HttpHeaders.COOKIE) == null
				&& request.getHeader(HttpHeaders.CONTENT_ENCODING) == null
				&& Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION)).size() == 1;
	}

	private static byte[] digest(byte[] body) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body))
					.getBytes(StandardCharsets.US_ASCII);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}

	private static void rejectBinding(HttpServletResponse response) throws IOException {
		reject(response, HttpStatus.FORBIDDEN, "REQUEST_NOT_SIGNED", "The credential does not authorise this request");
	}

	/** Fixed texts only, written directly: an error dispatch would be answered by the deny-all chain as 401. */
	private static void reject(HttpServletResponse response, HttpStatus status, String code, String detail)
			throws IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.getOutputStream().write(("{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase()
				+ "\",\"status\":" + status.value() + ",\"detail\":\"" + detail + "\",\"code\":\"" + code + "\"}")
				.getBytes(StandardCharsets.UTF_8));
	}
}

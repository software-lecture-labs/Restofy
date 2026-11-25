package com.restofy.kitchen.support;

import com.restofy.kitchen.infrastructure.security.KitchenAuthentication;
import com.restofy.kitchen.infrastructure.security.KitchenPrincipal;
import com.restofy.kitchen.infrastructure.security.StaffRole;
import jakarta.servlet.http.Cookie;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * TEST-ONLY identity fixture for the business tests. It skips the cookie and Auth steps (those are tested with signed
 * tokens by the security tests); production creates a {@link KitchenAuthentication} only after both. This must never
 * move to src/main.
 */
public final class KitchenTestAuth {

	/** Must be in {@code restofy.auth.allowed-origins} of the Kitchen test profile. */
	public static final String ORIGIN = "http://localhost:5000";
	/** A proof in Auth's {@code expiry.nonce.signature} shape that does not expire during a test run. */
	public static final String CSRF_PROOF = "4102444800." + "n".repeat(43) + "." + "s".repeat(43);

	private KitchenTestAuth() {
	}

	/**
	 * What a legitimate browser sends with a mutation: an allowed Origin and Auth's CSRF cookie echoed in the header.
	 * The application's real CSRF protection checks it; nothing is swapped out or disabled for tests.
	 */
	public static RequestPostProcessor csrf() {
		// Replaces rather than appends: some tests reuse one request builder, and a browser sends each of these once.
		return request -> {
			replaceHeader(request, "Origin", ORIGIN);
			replaceHeader(request, "X-CSRF-TOKEN", CSRF_PROOF);
			Cookie[] present = request.getCookies() == null ? new Cookie[0] : request.getCookies();
			request.setCookies(Stream.concat(
					Arrays.stream(present).filter(cookie -> !"restofy_csrf".equals(cookie.getName())),
					Stream.of(new Cookie("restofy_csrf", CSRF_PROOF))).toArray(Cookie[]::new));
			return request;
		};
	}

	private static void replaceHeader(MockHttpServletRequest request, String name, String value) {
		request.removeHeader(name);
		request.addHeader(name, value);
	}

	public static RequestPostProcessor kitchen(UUID restaurantId) {
		return as(restaurantId, StaffRole.KITCHEN);
	}

	public static RequestPostProcessor admin(UUID restaurantId) {
		return as(restaurantId, StaffRole.ADMIN);
	}

	/** Same as {@link #kitchen(UUID)} with a known user id, to assert who is recorded as the actor. */
	public static RequestPostProcessor kitchen(UUID restaurantId, UUID userId) {
		return SecurityMockMvcRequestPostProcessors
				.authentication(new KitchenAuthentication(new KitchenPrincipal(userId, restaurantId), StaffRole.KITCHEN));
	}

	private static RequestPostProcessor as(UUID restaurantId, StaffRole role) {
		return SecurityMockMvcRequestPostProcessors
				.authentication(new KitchenAuthentication(new KitchenPrincipal(UUID.randomUUID(), restaurantId), role));
	}
}

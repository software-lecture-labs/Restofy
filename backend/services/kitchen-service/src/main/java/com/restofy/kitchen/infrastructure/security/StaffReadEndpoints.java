package com.restofy.kitchen.infrastructure.security;

import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.List;
import java.util.stream.Stream;

/**
 * The staff requests that rely on the local token proof alone. This is a reviewed list, not "every GET": each
 * endpoint on it changes nothing in Kitchen or anywhere else, and the kitchen screen polls them, so they do not ask
 * Auth about the session. A revoked, disabled or re-roled session can therefore keep reading until its token expires,
 * at most 300 seconds after it was issued.
 *
 * <p>Everything not listed here is confirmed with Auth, including endpoints added later, until someone has reviewed
 * them for side effects. A test compares this list with every kitchen mapping of the application.
 *
 * <p>The closed products read asks the Menu service through a port that passes the restaurant only. If a Menu adapter
 * ever sends a staff identity or delegation with it, this endpoint must be reviewed again before that: it would pass on
 * a session Auth has not confirmed.
 */
final class StaffReadEndpoints {

	static final List<String> PATTERNS = List.of(
			"/api/v1/kitchen/orders",
			"/api/v1/kitchen/orders/{orderId}",
			"/api/v1/kitchen/orders/{orderId}/cancellation-requests",
			"/api/v1/kitchen/products/closed",
			"/api/v1/kitchen/reports/daily",
			"/api/v1/kitchen/waste");

	private StaffReadEndpoints() {
	}

	/** GET and HEAD of exactly these endpoints. */
	static RequestMatcher matcher() {
		PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
		return new OrRequestMatcher(PATTERNS.stream()
				.flatMap(pattern -> Stream.of(HttpMethod.GET, HttpMethod.HEAD)
						.map(method -> (RequestMatcher) paths.matcher(method, pattern)))
				.toList());
	}
}

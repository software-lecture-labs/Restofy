package com.restofy.kitchen.infrastructure.security;

import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The staff requests that rely on the local token proof alone. This is a reviewed list, not "every GET": each
 * endpoint on it changes nothing in Kitchen or anywhere else, and the kitchen screen polls them, so they do not ask
 * Auth about the session. A revoked, disabled or re-roled session can therefore keep reading until its token expires,
 * at most 300 seconds after it was issued.
 *
 * <p>Everything not listed here is confirmed with Auth, including endpoints added later, until someone has reviewed
 * them for side effects. A test requires every kitchen mapping of the application to be classified and requires this
 * list to be exactly the reads of that classification.
 *
 * <p>A path variable of a listed read matches only a UUID, which is what every id in these paths is. Without that, a
 * later endpoint with a fixed word in that place ({@code /orders/export}) would be routed to its own handler but still
 * be taken for the listed read here, and would skip the confirmation.
 *
 * <p>The closed products read asks the Menu service through a port that passes the restaurant only. If a Menu adapter
 * ever sends a staff identity or delegation with it, this endpoint must be reviewed again before that: it would pass on
 * a session Auth has not confirmed.
 */
public final class StaffReadEndpoints {

	public static final List<String> PATTERNS = List.of(
			"/api/v1/kitchen/orders",
			"/api/v1/kitchen/orders/{orderId}",
			"/api/v1/kitchen/orders/{orderId}/cancellation-requests",
			"/api/v1/kitchen/products/closed",
			"/api/v1/kitchen/reports/daily",
			"/api/v1/kitchen/waste");

	private static final Pattern VARIABLE = Pattern.compile("\\{(\\w+)}");
	private static final String UUID_ONLY =
			"{$1:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}";

	private StaffReadEndpoints() {
	}

	/** GET and HEAD of exactly these endpoints. */
	static RequestMatcher matcher() {
		PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
		return new OrRequestMatcher(PATTERNS.stream()
				.map(pattern -> VARIABLE.matcher(pattern).replaceAll(UUID_ONLY))
				.flatMap(pattern -> Stream.of(HttpMethod.GET, HttpMethod.HEAD)
						.map(method -> (RequestMatcher) paths.matcher(method, pattern)))
				.toList());
	}
}

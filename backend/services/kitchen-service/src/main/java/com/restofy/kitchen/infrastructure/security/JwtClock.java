package com.restofy.kitchen.infrastructure.security;

import java.time.Clock;

/**
 * jjwt's clock contract returns {@code java.util.Date}. The time itself comes from the injected
 * {@code java.time} clock; the conversion at the library boundary is all this class does, so the suppressed
 * "use java.time" rule covers nothing else.
 */
@SuppressWarnings("java:S2143")
final class JwtClock {

	private JwtClock() {
	}

	static io.jsonwebtoken.Clock of(Clock clock) {
		return () -> java.util.Date.from(clock.instant());
	}
}

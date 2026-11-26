package com.restofy.kitchen.infrastructure.security;

import java.time.Instant;

/** The JWT library's clock needs {@link java.util.Date}; this is the only place that creates one. */
@SuppressWarnings("java:S2143")
final class JwtDates {

	private JwtDates() {
	}

	static java.util.Date of(Instant instant) {
		return java.util.Date.from(instant);
	}
}

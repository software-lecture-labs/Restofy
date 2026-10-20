package com.restofy.kitchen.api.support;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared rules for optional query parameters: <b>absent</b> uses the default, while <b>present but empty, blank or
 * invalid</b> is 400. Values are never echoed back.
 */
public final class QueryParams {

	public static final int DEFAULT_SIZE = 20;
	public static final int MAX_SIZE = 100;

	private QueryParams() {
	}

	public static int parseInt(String name, String value, int defaultValue, int min, int max) {
		if (value == null) {
			return defaultValue;
		}
		try {
			int parsed = Integer.parseInt(value);
			if (parsed >= min && parsed <= max) {
				return parsed;
			}
		} catch (NumberFormatException _) {
			// falls through to the same 400 as an out-of-range value
		}
		throw badRequest(max == Integer.MAX_VALUE ? name + " must be an integer >= " + min
				: name + " must be an integer between " + min + " and " + max);
	}

	public static ResponseStatusException badRequest(String message) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
	}
}

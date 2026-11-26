package com.restofy.kitchen.infrastructure.security;

import com.restofy.kitchen.support.OrderCaller;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The receiver opens only when both settings are present and usable; a bad value stops the start without being shown. */
class OrderCallerVerifierConfigurationTests {

	private static final String KEYS = OrderCaller.publicKeys(OrderCaller.KEY_ID, OrderCaller.KEYS);

	private static OrderCallerVerifier verifier(String keys, String restaurants) {
		return new OrderCallerVerifier(Clock.systemUTC(), keys, restaurants);
	}

	private static boolean accepts(OrderCallerVerifier verifier) {
		byte[] body = OrderCaller.bytes("{}");
		String token = OrderCaller.authorization(OrderCaller.RESTAURANT, body).substring("Service ".length());
		return verifier.verify(token, "POST", OrderCaller.PATH).isPresent();
	}

	@Test
	void itIsOpenOnlyWhenKeysAndRestaurantsAreBothSet() {
		String restaurant = OrderCaller.RESTAURANT.toString();

		assertTrue(accepts(verifier(KEYS, restaurant)));
		assertFalse(accepts(verifier("", "")));
		assertFalse(accepts(verifier(KEYS, "")));
		assertFalse(accepts(verifier(KEYS, "   ")));
		assertFalse(accepts(verifier("", restaurant)));
		assertFalse(accepts(verifier(KEYS, UUID.randomUUID().toString())), "another restaurant only");
	}

	/** The restaurant list is shared with the Order service; set for Order alone it must not stop Kitchen. */
	@Test
	void aRestaurantListWithoutKeysIsNotEvenRead() {
		assertFalse(accepts(verifier("", "not-a-uuid")));
	}

	@ParameterizedTest
	@ValueSource(strings = { "SECRET-VALUE", "{}", "[]", "null", "{\"k\":\"SECRET-VALUE\"}", "{\"k\":\"\"}", "{\"k\":null}" })
	void anUnusableKeyMapStopsTheStartWithoutShowingIt(String keys) {
		String restaurant = UUID.randomUUID().toString();

		var e = assertThrows(IllegalStateException.class, () -> verifier(keys, restaurant));

		assertTrue(e.getMessage().startsWith("KITCHEN_ORDER_CALLER_PUBLIC_KEYS "), e.getMessage());
		assertFalse(e.getMessage().contains("SECRET-VALUE"));
		assertNull(e.getCause());
	}

	@Test
	void weakKeysBadKeyIdsAndDuplicateKeyIdsAreRefused() {
		String entry = KEYS.substring(1, KEYS.length() - 1);
		String restaurant = UUID.randomUUID().toString();
		for (String keys : new String[] { OrderCaller.publicKeys("weak", OrderCaller.rsa(1024)),
				OrderCaller.publicKeys("bad id", OrderCaller.KEYS), "{" + entry + "," + entry + "}" }) {
			assertThrows(IllegalStateException.class, () -> verifier(keys, restaurant));
		}
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"SECRET-VALUE", "1-2-3-4-5", "11111111-1111-4111-8111-111111111111,",
			"AAAAAAAA-1111-4111-8111-111111111111" })
	void anUnusableRestaurantListStopsTheStartWithoutShowingIt(String restaurants) {
		var e = assertThrows(IllegalStateException.class, () -> verifier(KEYS, restaurants));

		assertTrue(e.getMessage().startsWith("ORDER_KITCHEN_SERVICE_RESTAURANTS "), e.getMessage());
		assertFalse(e.getMessage().contains(restaurants));
	}
}

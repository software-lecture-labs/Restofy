package com.restofy.kitchen.infrastructure.security;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The authenticated staff identity as a value: two cooks or two restaurants are never equal, and the object keeps the
 * serialization contract its Spring Security base class declares. Kitchen is stateless and serializes no identity.
 */
class KitchenAuthenticationTests {

	private final UUID user = UUID.randomUUID();
	private final UUID restaurant = UUID.randomUUID();

	private static KitchenAuthentication staff(UUID user, UUID restaurant, StaffRole role) {
		return new KitchenAuthentication(new KitchenPrincipal(user, restaurant), role);
	}

	@Test
	void identitiesAreEqualOnlyForTheSameUserRestaurantAndRole() {
		var cook = staff(user, restaurant, StaffRole.KITCHEN);

		assertEquals(cook, staff(user, restaurant, StaffRole.KITCHEN));
		assertEquals(cook.hashCode(), staff(user, restaurant, StaffRole.KITCHEN).hashCode());
		assertNotEquals(cook, staff(UUID.randomUUID(), restaurant, StaffRole.KITCHEN));
		assertNotEquals(cook, staff(user, UUID.randomUUID(), StaffRole.KITCHEN));
		assertNotEquals(cook, staff(user, restaurant, StaffRole.ADMIN));
	}

	@Test
	void theIdentitySurvivesSerializationUnchanged() throws IOException, ClassNotFoundException {
		var cook = staff(user, restaurant, StaffRole.KITCHEN);
		var bytes = new ByteArrayOutputStream();
		try (var out = new ObjectOutputStream(bytes)) {
			out.writeObject(cook);
		}

		try (var in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			var restored = (KitchenAuthentication) in.readObject();
			assertEquals(cook, restored);
			assertEquals(new KitchenPrincipal(user, restaurant), restored.getPrincipal());
		}
	}
}

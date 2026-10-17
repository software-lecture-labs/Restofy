package com.restofy.kitchen.support;

import com.restofy.kitchen.infrastructure.security.KitchenAuthentication;
import com.restofy.kitchen.infrastructure.security.KitchenPrincipal;
import com.restofy.kitchen.infrastructure.security.StaffRole;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

/**
 * TEST-ONLY identity fixture. Production has no component that creates a {@link KitchenAuthentication} until the
 * Auth contract exists; this must never move to src/main.
 */
public final class KitchenTestAuth {

	private KitchenTestAuth() {
	}

	public static RequestPostProcessor kitchen(UUID restaurantId) {
		return as(restaurantId, StaffRole.KITCHEN);
	}

	public static RequestPostProcessor admin(UUID restaurantId) {
		return as(restaurantId, StaffRole.ADMIN);
	}

	private static RequestPostProcessor as(UUID restaurantId, StaffRole role) {
		return SecurityMockMvcRequestPostProcessors
				.authentication(new KitchenAuthentication(new KitchenPrincipal(UUID.randomUUID(), restaurantId), role));
	}
}

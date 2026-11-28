package com.restofy.kitchen.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.ResourcePropertySource;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The application's own {@code application.properties} reads Auth's public key from Kitchen's ENV name only. */
class KitchenAuthEnvBindingTests {

	/** Resolves against the given ENV values and the real property file; the machine's environment is not consulted. */
	private static String staffPublicKey(Map<String, Object> env) throws IOException {
		var sources = new MutablePropertySources();
		sources.addFirst(new MapPropertySource("env", env));
		sources.addLast(new ResourcePropertySource(new ClassPathResource("application.properties")));
		return new PropertySourcesPropertyResolver(sources).getProperty("restofy.auth.staff-public-key");
	}

	@Test
	void theKeyComesFromKitchensOwnEnvName() throws IOException {
		assertEquals("fixture-value", staffPublicKey(Map.of("KITCHEN_AUTH_STAFF_PUBLIC_KEY", "fixture-value")));
	}

	@Test
	void withoutTheEnvValueTheKeyIsBlankAndAnotherServicesKeyIsNotBorrowed() throws IOException {
		assertEquals("", staffPublicKey(Map.of("WAITER_AUTH_STAFF_PUBLIC_KEY", "waiter",
				"ORDER_AUTH_STAFF_PUBLIC_KEY", "order")));
	}
}

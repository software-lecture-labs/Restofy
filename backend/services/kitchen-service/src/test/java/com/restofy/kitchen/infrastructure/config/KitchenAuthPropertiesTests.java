package com.restofy.kitchen.infrastructure.config;

import com.restofy.kitchen.support.StaffTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Staff authentication is either off (blank key) or completely and safely configured; anything else stops startup. */
class KitchenAuthPropertiesTests {

	private static final String KEY = StaffTokens.publicKey();
	private static final List<String> ORIGINS = List.of("http://localhost:5000", "https://staff.example.test:8443");

	private static KitchenAuthProperties enabled(String url, String key, String issuer, long ttl, List<String> origins,
			long connect, long read) {
		return new KitchenAuthProperties(url, key, issuer, ttl, origins, connect, read);
	}

	private static KitchenAuthProperties withUrl(String url) {
		return enabled(url, KEY, "restofy-auth", 300, ORIGINS, 3000, 5000);
	}

	@Test
	void aCompleteConfigurationEnablesStaffAuthenticationAndBuildsTheSessionAddress() {
		var properties = withUrl("https://auth.example.test:8443/");

		assertTrue(properties.staffAuthenticationEnabled());
		assertEquals(URI.create("https://auth.example.test:8443/api/v1/auth/session"), properties.sessionUri());
		assertEquals(3, properties.connectTimeout().toSeconds());
		assertEquals(5, properties.readTimeout().toSeconds());
	}

	@Test
	void aBlankKeyLeavesAuthenticationOffWithoutDemandingTheOtherValues() {
		// The shared Compose defaults today: plain-HTTP Auth address and no Kitchen key.
		var properties = enabled("http://auth-service:8080", "  ", "restofy-auth", 900, ORIGINS, 3000, 5000);

		assertFalse(properties.staffAuthenticationEnabled());
		assertDoesNotThrow(() -> enabled(null, null, null, 0, null, 0, 0));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "http://auth-service:8080", "http://localhost:5051", "auth-service:8080",
			"https://", "https://user:pw@auth.example.test", "https://auth.example.test/api",
			"https://auth.example.test?x=1", "https://auth.example.test#f", "https://auth.example.test:99999",
			"ftp://auth.example.test", "https://exa mple" })
	void theStaffCookieIsOnlyEverSentToABareHttpsOrigin(String url) {
		var failure = assertThrows(IllegalStateException.class, () -> withUrl(url));

		assertEquals("AUTH_SERVICE_URL must be an HTTPS origin while staff authentication is on", failure.getMessage());
	}

	@Test
	void weakOrUnreadableKeysStopStartupWithoutQuotingThem() throws NoSuchAlgorithmException {
		KeyPairGenerator weak = KeyPairGenerator.getInstance("RSA");
		weak.initialize(1024);
		String weakKey = Base64.getEncoder().encodeToString(weak.generateKeyPair().getPublic().getEncoded());
		String privateKey = Base64.getEncoder().encodeToString(StaffTokens.KEYS.getPrivate().getEncoded());

		for (String key : List.of(weakKey, privateKey, "not-base64!", Base64.getEncoder().encodeToString(new byte[64]))) {
			var failure = assertThrows(IllegalStateException.class,
					() -> enabled("https://auth.example.test", key, "restofy-auth", 300, ORIGINS, 3000, 5000));
			assertTrue(failure.getMessage().startsWith("restofy.auth.staff-public-key"), failure.getMessage());
			assertFalse(failure.getMessage().contains(key));
		}
	}

	@ParameterizedTest
	@ValueSource(longs = { 0, -1, 301, 900 })
	void aLifetimeBeyondAuthsAccessLifetimeIsRefused(long ttl) {
		assertThrows(IllegalStateException.class,
				() -> enabled("https://auth.example.test", KEY, "restofy-auth", ttl, ORIGINS, 3000, 5000));
	}

	@Test
	void issuerOriginsAndTimeoutsAreRequiredWhileOn() {
		String url = "https://auth.example.test";
		assertThrows(IllegalStateException.class, () -> enabled(url, KEY, " ", 300, ORIGINS, 3000, 5000));
		assertThrows(IllegalStateException.class, () -> enabled(url, KEY, "restofy-auth", 300, List.of(), 3000, 5000));
		assertThrows(IllegalStateException.class, () -> enabled(url, KEY, "restofy-auth", 300, ORIGINS, 0, 5000));
		assertThrows(IllegalStateException.class, () -> enabled(url, KEY, "restofy-auth", 300, ORIGINS, 3000, 0));
	}

	@ParameterizedTest
	@ValueSource(strings = { "*", "https://*.example.test", "https://staff.example.test/", "https://staff.example.test/app",
			"null", "staff.example.test", "https://user@staff.example.test", "https://staff.example.test:0" })
	void onlyExplicitOriginsAreAllowedWhetherAuthenticationIsOnOrOff(String origin) {
		assertThrows(IllegalStateException.class,
				() -> enabled("", "", "restofy-auth", 300, List.of(origin), 3000, 5000), origin);
	}
}

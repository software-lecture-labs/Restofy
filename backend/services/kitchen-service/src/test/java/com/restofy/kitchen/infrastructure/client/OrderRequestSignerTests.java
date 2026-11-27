package com.restofy.kitchen.infrastructure.client;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The token Kitchen signs for the Order service, read back the way the Order service's Kitchen verifier in PR #47 reads
 * it. Keys are generated for the test run only. This shows the token's form; it is not the Order service accepting it.
 */
class OrderRequestSignerTests {

	private static final Instant NOW = Instant.parse("2026-11-27T10:15:30Z");
	private static final String PATH = "/api/v1/internal/order-events/kitchen";

	private static KeyPair keys;
	private static KeyPair otherKeys;

	@BeforeAll
	static void generateKeys() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		keys = generator.generateKeyPair();
		otherKeys = generator.generateKeyPair();
	}

	private static String privateKey(KeyPair pair) {
		return Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
	}

	private static OrderRequestSigner signer() {
		return new OrderRequestSigner(Clock.fixed(NOW, ZoneOffset.UTC), "kitchen-2026-11", privateKey(keys));
	}

	/** The checks of the Order service's Kitchen verifier (PR #47), with its clock at {@code at}. */
	private static Jws<Claims> read(String token, PublicKey key, Instant at) {
		return Jwts.parser().keyLocator(header -> {
					if (!"kitchen-2026-11".equals(header.get("kid"))) {
						throw new JwtException("Unknown Kitchen signing key");
					}
					return key;
				}).sig().clear().add(Jwts.SIG.RS256).and()
				.clock(() -> Date.from(at))
				.requireIssuer("restofy-kitchen").requireSubject("kitchen-service").requireAudience("restofy-order")
				.require("token_type", "service").require("scope", "order:kitchen-events").build()
				.parseSignedClaims(token);
	}

	@Test
	void theTokenCarriesTheOrderServicesKitchenClaimsAndIsBoundToTheRequest() {
		UUID restaurant = UUID.randomUUID();
		byte[] body = "{\"eventId\":\"x\"}".getBytes(StandardCharsets.UTF_8);

		Jws<Claims> token = read(signer().credentialFor(restaurant, PATH, body), keys.getPublic(), NOW);

		assertEquals("RS256", token.getHeader().getAlgorithm());
		Claims claims = token.getPayload();
		assertEquals(claims.getIssuedAt(), claims.getNotBefore());
		assertEquals(NOW.getEpochSecond(), claims.getIssuedAt().toInstant().getEpochSecond());
		assertEquals(30, claims.getExpiration().toInstant().getEpochSecond() - claims.getIssuedAt().toInstant().getEpochSecond());
		assertEquals(claims.getId(), UUID.fromString(claims.getId()).toString());
		assertEquals(restaurant.toString(), claims.get("restaurant_id", String.class));
		assertEquals("POST", claims.get("method", String.class));
		assertEquals(PATH, claims.get("target", String.class));
		assertEquals(OrderRequestSigner.sha256(body), claims.get("body_sha256", String.class));
		assertTrue(claims.get("body_sha256", String.class).matches("[a-f0-9]{64}"));
	}

	@Test
	void everyTokenHasItsOwnIdAndTheDigestFollowsTheBody() {
		UUID restaurant = UUID.randomUUID();
		Claims first = read(signer().credentialFor(restaurant, PATH, new byte[] {1}), keys.getPublic(), NOW).getPayload();
		Claims second = read(signer().credentialFor(restaurant, PATH, new byte[] {2}), keys.getPublic(), NOW).getPayload();

		assertNotEquals(first.getId(), second.getId());
		assertNotEquals(first.get("body_sha256"), second.get("body_sha256"));
		assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", OrderRequestSigner.sha256(new byte[0]));
	}

	@Test
	void theTokenIsRefusedAfterItsLifetimeAndWithAnotherKey() {
		String token = signer().credentialFor(UUID.randomUUID(), PATH, new byte[0]);
		PublicKey right = keys.getPublic();
		PublicKey wrong = otherKeys.getPublic();
		Instant expired = NOW.plusSeconds(31);

		assertThrows(JwtException.class, () -> read(token, right, expired));
		assertThrows(JwtException.class, () -> read(token, wrong, NOW));
	}

	@Test
	void aTokenIsNotTheKey() {
		String token = signer().credentialFor(UUID.randomUUID(), PATH, new byte[0]);

		assertFalse(token.contains(privateKey(keys).substring(0, 40)));
		assertEquals(3, token.split("\\.").length);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"has space", "x/y", "0123456789012345678901234567890123456789012345678901234567890123456789"})
	void anInvalidKeyIdStopsTheStart(String keyId) {
		Clock clock = Clock.systemUTC();
		String key = privateKey(keys);
		IllegalStateException failure = assertThrows(IllegalStateException.class, () -> new OrderRequestSigner(clock, keyId, key));

		assertTrue(failure.getMessage().startsWith("KITCHEN_ORDER_SIGNING_KEY_ID"));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"not base64 !", "c2VjcmV0LXZhbHVl"})
	void anUnusableKeyStopsTheStartWithoutRepeatingTheValue(String value) {
		Clock clock = Clock.systemUTC();
		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> new OrderRequestSigner(clock, "k1", value));

		assertEquals("KITCHEN_ORDER_SIGNING_PRIVATE_KEY must be a Base64 PKCS#8 RSA key of at least 2048 bits", failure.getMessage());
		assertTrue(value == null || value.isEmpty() || !failure.getMessage().contains(value));
	}

	@Test
	void aShortRsaKeyIsRefused() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(1024);
		String weak = privateKey(generator.generateKeyPair());
		Clock clock = Clock.systemUTC();

		assertThrows(IllegalStateException.class, () -> new OrderRequestSigner(clock, "k1", weak));
	}

	@Test
	void aKeyWithSurroundingBlanksIsAccepted() {
		String key = "\n " + privateKey(keys) + " \n";
		Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

		String token = new OrderRequestSigner(clock, "kitchen-2026-11", key).credentialFor(UUID.randomUUID(), PATH, new byte[0]);

		assertEquals("kitchen-service", read(token, keys.getPublic(), NOW).getPayload().getSubject());
	}
}

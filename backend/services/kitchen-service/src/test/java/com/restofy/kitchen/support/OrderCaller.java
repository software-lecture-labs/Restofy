package com.restofy.kitchen.support;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Test double for the Order service's request signer: the same header and claims, signed with a key generated for this
 * test run. No key is stored in the repository.
 */
public final class OrderCaller {

	public static final String PATH = "/api/v1/internal/kitchen/order-events";
	public static final String KEY_ID = "order-test-1";
	public static final KeyPair KEYS = rsa(2048);
	public static final UUID RESTAURANT = UUID.randomUUID();
	public static final UUID SECOND_RESTAURANT = UUID.randomUUID();

	private OrderCaller() {
	}

	/** Opens the receiver for the two test restaurants. */
	public static void configure(DynamicPropertyRegistry registry) {
		registry.add("restofy.order-caller.public-keys", () -> publicKeys(KEY_ID, KEYS));
		registry.add("restofy.order-caller.restaurants", () -> RESTAURANT + ", " + SECOND_RESTAURANT);
	}

	public static String publicKeys(String keyId, KeyPair keys) {
		return "{\"" + keyId + "\":\"" + Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()) + "\"}";
	}

	/** The claims of a correct credential for these bytes; tests change or remove one at a time. */
	public static Map<String, Object> claims(UUID restaurantId, byte[] body) {
		long issuedAt = Instant.now().getEpochSecond();
		Map<String, Object> claims = new LinkedHashMap<>();
		claims.put("iss", "restofy-order");
		claims.put("sub", "order-service");
		claims.put("aud", "restofy-kitchen");
		claims.put("jti", UUID.randomUUID().toString());
		claims.put("iat", issuedAt);
		claims.put("nbf", issuedAt);
		claims.put("exp", issuedAt + 30);
		claims.put("token_type", "service");
		claims.put("scope", "kitchen:order-events");
		claims.put("restaurant_id", restaurantId.toString());
		claims.put("method", "POST");
		claims.put("target", PATH);
		claims.put("body_sha256", sha256(body));
		return claims;
	}

	public static String sign(Map<String, Object> claims, String keyId, KeyPair keys) {
		JwtBuilder builder = Jwts.builder().header().keyId(keyId).and();
		claims.forEach(builder::claim);
		return builder.signWith(keys.getPrivate(), Jwts.SIG.RS256).compact();
	}

	/** The Authorization header the Order service would send for these bytes. */
	public static String authorization(UUID restaurantId, byte[] body) {
		return "Service " + sign(claims(restaurantId, body), KEY_ID, KEYS);
	}

	public static byte[] bytes(String json) {
		return json.getBytes(StandardCharsets.UTF_8);
	}

	public static String sha256(byte[] body) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	public static KeyPair rsa(int bits) {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(bits);
			return generator.generateKeyPair();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** An "order accepted" event with the fields Kitchen requires, as JSON. */
	public static String accepted(UUID restaurantId, UUID orderId, UUID eventId, long version, String note) {
		return """
				{"eventId":"%s","eventType":"OrderAccepted","schemaVersion":1,"occurredAt":"2026-11-26T09:00:00Z",\
				"correlationId":"corr-1","restaurantId":"%s","orderId":"%s","aggregateVersion":%d,"orderNumber":"A-100",\
				"tableId":"88888888-8888-4888-8888-888888888888","tableSessionId":"44444444-4444-4444-8444-444444444444",\
				"tableNumber":"12","items":[{"orderItemId":"%s",\
				"productId":"66666666-6666-4666-8666-666666666666","productName":"Burger","quantity":2,"note":"%s",\
				"options":[{"action":"REMOVE","optionId":"77777777-7777-4777-8777-777777777777","name":"Soğan",\
				"quantity":1}]}]}""".formatted(eventId, restaurantId, orderId, version, itemId(orderId), note);
	}

	/** One line id per order: a redelivery repeats it, and no other order of the restaurant may reuse it. */
	public static UUID itemId(UUID orderId) {
		return UUID.nameUUIDFromBytes(("item-" + orderId).getBytes(StandardCharsets.UTF_8));
	}
}

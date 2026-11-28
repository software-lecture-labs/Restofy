package com.restofy.kitchen.support;

import io.jsonwebtoken.Jwts;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * TEST-ONLY producer of access tokens in the shape of Auth's {@code StaffJwtTokens.issue}, signed with a key pair
 * generated for this JVM. No real key or credential is involved.
 */
public final class StaffTokens {

	public static final String ISSUER = "restofy-auth";
	public static final KeyPair KEYS = generate();

	private StaffTokens() {
	}

	public static String publicKey() {
		return Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded());
	}

	/** The claims Auth puts into a staff access token that was issued at {@code now}. */
	public static Map<String, Object> claims(String role, UUID restaurantId, UUID userId, Instant now) {
		Map<String, Object> claims = new LinkedHashMap<>();
		claims.put("sub", userId.toString());
		claims.put("iss", ISSUER);
		claims.put("aud", List.of("restofy-staff-api"));
		claims.put("iat", now.getEpochSecond());
		claims.put("nbf", now.getEpochSecond());
		claims.put("exp", now.getEpochSecond() + 300);
		claims.put("jti", UUID.randomUUID().toString());
		claims.put("sid", UUID.randomUUID().toString());
		claims.put("role", role);
		claims.put("auth_state", "AUTHENTICATED");
		claims.put("restaurant_id", restaurantId.toString());
		claims.put("token_type", "access");
		claims.put("auth_policy", "q5-idle-v1");
		return claims;
	}

	public static String access(String role, UUID restaurantId, UUID userId) {
		return sign(claims(role, restaurantId, userId, Instant.now()));
	}

	/** A token issued at {@code now} with some claims changed or removed. */
	public static String altered(Instant now, Consumer<Map<String, Object>> change) {
		Map<String, Object> claims = claims("KITCHEN", UUID.randomUUID(), UUID.randomUUID(), now);
		change.accept(claims);
		return sign(claims);
	}

	public static String sign(Map<String, Object> claims) {
		return sign(claims, KEYS.getPrivate());
	}

	public static String sign(Map<String, Object> claims, PrivateKey key) {
		return Jwts.builder().claims(claims).signWith(key, Jwts.SIG.RS256).compact();
	}

	public static KeyPair generate() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			return generator.generateKeyPair();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}

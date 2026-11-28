package com.restofy.kitchen.infrastructure.client;

import io.jsonwebtoken.Jwts;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Kitchen's credential towards the Order service: a short-lived RS256 token bound to exactly one request. It follows
 * the Kitchen -> Order service token written down by the Order service in PR #47 (not on {@code dev} yet), which has the
 * same structure as the QR -> Order token on {@code dev}: issuer, subject, audience, scope and token type fixed; a unique
 * id; issued-at equal to not-before; at most 30 seconds valid; and the restaurant, method, path and SHA-256 of the exact
 * body bytes signed in. A token therefore cannot be replayed for another restaurant, path or body.
 *
 * <p>The private key never leaves Kitchen and is never logged; configuration errors name the setting, never its value.
 */
public class OrderRequestSigner implements OrderServiceCredentials {

	static final String ISSUER = "restofy-kitchen";
	static final String SUBJECT = "kitchen-service";
	static final String AUDIENCE = "restofy-order";
	static final String SCOPE = "order:kitchen-events";
	static final long LIFETIME_SECONDS = 30;
	private static final String METHOD = "POST";
	private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
	private static final int MIN_KEY_BITS = 2048;

	private final Clock clock;
	private final String keyId;
	private final RSAPrivateKey privateKey;

	public OrderRequestSigner(Clock clock, String keyId, String encodedPrivateKey) {
		if (keyId == null || !KEY_ID.matcher(keyId).matches()) {
			throw new IllegalStateException("KITCHEN_ORDER_SIGNING_KEY_ID must be 1-64 characters of A-Z, a-z, 0-9, '.', '_' or '-'");
		}
		this.clock = clock;
		this.keyId = keyId;
		this.privateKey = readKey(encodedPrivateKey);
	}

	@Override
	public String credentialFor(UUID restaurantId, String path, byte[] body) {
		long issuedAt = clock.instant().getEpochSecond();
		return Jwts.builder().header().keyId(keyId).and()
				.issuer(ISSUER).subject(SUBJECT).audience().add(AUDIENCE).and()
				.id(UUID.randomUUID().toString())
				.claim("iat", issuedAt).claim("nbf", issuedAt).claim("exp", issuedAt + LIFETIME_SECONDS)
				.claim("token_type", "service").claim("scope", SCOPE)
				.claim("restaurant_id", restaurantId.toString())
				.claim("method", METHOD).claim("target", path).claim("body_sha256", sha256(body))
				.signWith(privateKey, Jwts.SIG.RS256).compact();
	}

	static String sha256(byte[] body) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
		} catch (NoSuchAlgorithmException unavailable) {
			throw new IllegalStateException("SHA-256 is unavailable", unavailable);
		}
	}

	private static RSAPrivateKey readKey(String value) {
		try {
			var key = KeyFactory.getInstance("RSA")
					.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(value == null ? "" : value.strip())));
			if (key instanceof RSAPrivateKey rsa && rsa.getModulus().bitLength() >= MIN_KEY_BITS) {
				return rsa;
			}
		} catch (IllegalArgumentException | GeneralSecurityException _) {
			// The value and the parser's message are never repeated: they could contain key material.
		}
		throw new IllegalStateException("KITCHEN_ORDER_SIGNING_PRIVATE_KEY must be a Base64 PKCS#8 RSA key of at least "
				+ MIN_KEY_BITS + " bits");
	}
}

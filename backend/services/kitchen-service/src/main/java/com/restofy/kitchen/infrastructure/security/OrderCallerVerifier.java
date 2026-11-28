package com.restofy.kitchen.infrastructure.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Verifies the signed credential the Order service sends with an order event (the contract of the Order side:
 * RS256 with a key id, single use endpoint, at most 30 seconds, bound to method, path, restaurant and body digest).
 * Kitchen holds only Order's public keys; it issues no credential and trusts no other caller.
 *
 * <p>Closed unless both the key map and the restaurant list are configured: the restaurant list is shared with the
 * Order service, so either one alone must not open anything. A configured but unusable value stops the application
 * without printing it.
 *
 * <p>The token id is required but not remembered. A captured credential only allows the identical body to be sent to
 * the identical path within its lifetime, and the only applied event is stored under durable unique keys (event id,
 * and order per restaurant) that are never deleted. Any further event type must be deduplicated just as durably
 * before it is applied.
 */
@Component
public class OrderCallerVerifier {

	static final int MAX_TOKEN_LENGTH = 4096;
	static final String ISSUER = "restofy-order";
	static final String SUBJECT = "order-service";
	static final String AUDIENCE = "restofy-kitchen";
	static final String SCOPE = "kitchen:order-events";
	static final long MAX_LIFETIME_SECONDS = 30;

	private static final Logger log = LoggerFactory.getLogger(OrderCallerVerifier.class);
	private static final String KEYS_SETTING = "KITCHEN_ORDER_CALLER_PUBLIC_KEYS";
	private static final String RESTAURANTS_SETTING = "ORDER_KITCHEN_SERVICE_RESTAURANTS";
	private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
	private static final Pattern DIGEST = Pattern.compile("[a-f0-9]{64}");

	private final Clock clock;
	private final Set<UUID> restaurants;
	/** Null while the endpoint is closed. */
	private final JwtParser parser;

	/** What a verified credential allows: these body bytes, for this restaurant. */
	record VerifiedCredential(UUID restaurantId, String bodyDigest) {
	}

	public OrderCallerVerifier(Clock clock,
			@Value("${restofy.order-caller.public-keys:}") String publicKeys,
			@Value("${restofy.order-caller.restaurants:}") String restaurantIds) {
		this.clock = clock;
		if (publicKeys.isBlank() || restaurantIds.isBlank()) {
			log.info("The Order event endpoint is closed: {} and {} are not both set", KEYS_SETTING, RESTAURANTS_SETTING);
			this.restaurants = Set.of();
			this.parser = null;
			return;
		}
		this.restaurants = readRestaurants(restaurantIds);
		Map<String, RSAPublicKey> keys = readKeys(publicKeys);
		this.parser = Jwts.parser()
				.keyLocator(header -> {
					RSAPublicKey key = header.get("kid") instanceof String id ? keys.get(id) : null;
					if (key == null) {
						throw new JwtException("Unknown Order signing key");
					}
					return key;
				})
				.sig().clear().add(Jwts.SIG.RS256).and()
				.clock(() -> JwtDates.of(clock.instant()))
				.requireIssuer(ISSUER)
				.requireSubject(SUBJECT)
				.requireAudience(AUDIENCE)
				.require("token_type", "service")
				.require("scope", SCOPE)
				.build();
	}

	/** Empty for every credential that is not exactly what the contract describes, and always while closed. */
	Optional<VerifiedCredential> verify(String token, String expectedMethod, String expectedTarget) {
		if (parser == null || token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) {
			return Optional.empty();
		}
		try {
			Claims claims = parser.parseSignedClaims(token).getPayload();
			if (!isShortLived(claims) || !isCanonicalUuid(claims.getId())) {
				return Optional.empty();
			}
			String restaurant = claims.get("restaurant_id", String.class);
			String digest = claims.get("body_sha256", String.class);
			if (!isCanonicalUuid(restaurant) || !restaurants.contains(UUID.fromString(restaurant))
					|| !expectedMethod.equals(claims.get("method", String.class))
					|| !expectedTarget.equals(claims.get("target", String.class))
					|| digest == null || !DIGEST.matcher(digest).matches()) {
				return Optional.empty();
			}
			return Optional.of(new VerifiedCredential(UUID.fromString(restaurant), digest));
		} catch (JwtException | IllegalArgumentException _) {
			return Optional.empty();
		}
	}

	/** Issued now or earlier, not yet expired, valid from the moment of issue and for no longer than the contract says. */
	private boolean isShortLived(Claims claims) {
		if (claims.getIssuedAt() == null || claims.getExpiration() == null
				|| !claims.getIssuedAt().equals(claims.getNotBefore())) {
			return false;
		}
		Instant issued = claims.getIssuedAt().toInstant();
		Instant expires = claims.getExpiration().toInstant();
		Instant now = clock.instant();
		return !issued.isAfter(now) && expires.isAfter(now) && !expires.isAfter(issued.plusSeconds(MAX_LIFETIME_SECONDS));
	}

	private static boolean isCanonicalUuid(String value) {
		if (value == null) {
			return false;
		}
		try {
			return UUID.fromString(value).toString().equals(value);
		} catch (IllegalArgumentException _) {
			return false;
		}
	}

	private static Set<UUID> readRestaurants(String configured) {
		Set<String> values = Arrays.stream(configured.split(",", -1)).map(String::trim).collect(Collectors.toSet());
		if (!values.stream().allMatch(OrderCallerVerifier::isCanonicalUuid)) {
			throw new IllegalStateException(RESTAURANTS_SETTING + " must list authorised restaurant UUIDs");
		}
		return values.stream().map(UUID::fromString).collect(Collectors.toUnmodifiableSet());
	}

	/** A JSON object of key id to Base64 X.509 RSA public key, the format the other services use. */
	private static Map<String, RSAPublicKey> readKeys(String configured) {
		try {
			JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
			Map<String, String> entries = json.readValue(configured, new TypeReference<Map<String, String>>() {
			});
			if (entries == null || entries.isEmpty()) {
				throw new IllegalArgumentException("no key");
			}
			KeyFactory factory = KeyFactory.getInstance("RSA");
			Map<String, RSAPublicKey> keys = new HashMap<>();
			for (Map.Entry<String, String> entry : entries.entrySet()) {
				RSAPublicKey key = (RSAPublicKey) factory.generatePublic(
						new X509EncodedKeySpec(Base64.getDecoder().decode(entry.getValue())));
				if (key.getModulus().bitLength() < 2048 || !KEY_ID.matcher(entry.getKey()).matches()) {
					throw new IllegalArgumentException("unusable key");
				}
				keys.put(entry.getKey(), key);
			}
			return Map.copyOf(keys);
		} catch (Exception _) {
			// The cause is dropped on purpose: parser messages may quote the configured value.
			throw new IllegalStateException(KEYS_SETTING + " must contain RSA public keys of at least 2048 bits");
		}
	}
}

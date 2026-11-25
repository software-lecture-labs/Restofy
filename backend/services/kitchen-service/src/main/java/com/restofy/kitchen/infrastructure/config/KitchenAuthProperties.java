package com.restofy.kitchen.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * How Kitchen verifies the staff session Auth issued. Every value comes from configuration, never from a request.
 *
 * <p>{@code staffPublicKey} (Base64 X.509 RSA public key of Auth's staff JWTs) switches staff authentication on.
 * While it is blank nothing authenticates and every staff request is a 401: no key is guessed or built in. Once it
 * is set, every other value must be usable or the application does not start. {@code serviceUrl} must then be an
 * HTTPS origin, because the staff cookie is sent to Auth for the session check. Startup messages name the setting,
 * never its value.
 */
@ConfigurationProperties("restofy.auth")
public record KitchenAuthProperties(String serviceUrl, String staffPublicKey, String issuer, long accessTtlSeconds,
		List<String> allowedOrigins, long connectTimeoutMs, long readTimeoutMs) {

	/** Auth's staff access token lives 300 seconds; a longer configured lifetime is never accepted. */
	public static final long MAX_ACCESS_TTL_SECONDS = 300;
	private static final int MIN_RSA_BITS = 2048;
	private static final String SESSION_PATH = "/api/v1/auth/session";

	public KitchenAuthProperties {
		serviceUrl = strip(serviceUrl);
		staffPublicKey = strip(staffPublicKey);
		issuer = strip(issuer);
		allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins.stream().map(String::strip)
				.filter(origin -> !origin.isEmpty()).distinct().toList();
		if (!allowedOrigins.stream().allMatch(KitchenAuthProperties::validOrigin)) {
			throw new IllegalStateException("CORS_ALLOWED_ORIGINS must contain explicit http(s) origins only");
		}
		if (!staffPublicKey.isEmpty()) {
			require(!issuer.isEmpty(), "JWT_ISSUER must not be blank while staff authentication is on");
			require(accessTtlSeconds > 0 && accessTtlSeconds <= MAX_ACCESS_TTL_SECONDS,
					"JWT_ACCESS_TTL_SECONDS must be 1 to " + MAX_ACCESS_TTL_SECONDS + " while staff authentication is on");
			require(!allowedOrigins.isEmpty(), "CORS_ALLOWED_ORIGINS must not be empty while staff authentication is on");
			require(connectTimeoutMs > 0 && readTimeoutMs > 0,
					"REST_CLIENT_CONNECT_TIMEOUT_MS and REST_CLIENT_READ_TIMEOUT_MS must be positive");
			parseKey(staffPublicKey);
			parseSessionUri(serviceUrl);
		}
	}

	public boolean staffAuthenticationEnabled() {
		return !staffPublicKey.isEmpty();
	}

	/** Only meaningful while staff authentication is on. */
	public RSAPublicKey rsaPublicKey() {
		return parseKey(staffPublicKey);
	}

	/** Auth's session endpoint on the configured HTTPS origin; only meaningful while staff authentication is on. */
	public URI sessionUri() {
		return parseSessionUri(serviceUrl);
	}

	public Duration connectTimeout() {
		return Duration.ofMillis(connectTimeoutMs);
	}

	public Duration readTimeout() {
		return Duration.ofMillis(readTimeoutMs);
	}

	private static String strip(String value) {
		return value == null ? "" : value.strip();
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new IllegalStateException(message);
		}
	}

	private static URI parseSessionUri(String base) {
		try {
			URI uri = URI.create(base);
			if ("https".equalsIgnoreCase(uri.getScheme()) && isBareAuthority(uri, "/")) {
				return URI.create("https://" + uri.getRawAuthority() + SESSION_PATH);
			}
		} catch (IllegalArgumentException _) {
			// Configuration values never appear in startup errors.
		}
		throw new IllegalStateException("AUTH_SERVICE_URL must be an HTTPS origin while staff authentication is on");
	}

	private static boolean validOrigin(String origin) {
		try {
			URI uri = URI.create(origin);
			return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && isBareAuthority(uri, "");
		} catch (IllegalArgumentException _) {
			return false;
		}
	}

	/** A host with an optional valid port and nothing else; {@code trailingPath} is the one extra path allowed. */
	private static boolean isBareAuthority(URI uri, String trailingPath) {
		int port = uri.getPort();
		String path = uri.getRawPath();
		return uri.getHost() != null && (port == -1 || (port > 0 && port <= 65535)) && uri.getRawUserInfo() == null
				&& uri.getRawQuery() == null && uri.getRawFragment() == null
				&& (path.isEmpty() || path.equals(trailingPath));
	}

	private static RSAPublicKey parseKey(String encoded) {
		try {
			var key = KeyFactory.getInstance("RSA")
					.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
			if (key instanceof RSAPublicKey rsa && rsa.getModulus().bitLength() >= MIN_RSA_BITS) {
				return rsa;
			}
		} catch (GeneralSecurityException | IllegalArgumentException _) {
			// Neither key material nor the parsing error is reported.
		}
		throw new IllegalStateException(
				"restofy.auth.staff-public-key must be a Base64 X.509 RSA public key of at least " + MIN_RSA_BITS + " bits");
	}
}

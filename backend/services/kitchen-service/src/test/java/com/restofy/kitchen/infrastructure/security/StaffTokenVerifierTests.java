package com.restofy.kitchen.infrastructure.security;

import com.restofy.kitchen.infrastructure.config.KitchenAuthProperties;
import com.restofy.kitchen.support.StaffTokens;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The local proof of a staff access token: every claim and signature rule of Auth's contract. */
class StaffTokenVerifierTests {

	private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	private static KitchenAuthProperties properties(String publicKey) {
		return new KitchenAuthProperties("https://auth.example.test", publicKey, StaffTokens.ISSUER, 300,
				List.of("http://localhost:5000"), 3000, 5000);
	}

	private final StaffTokenVerifier verifier = new StaffTokenVerifier(properties(StaffTokens.publicKey()), CLOCK);

	@ParameterizedTest
	@EnumSource(StaffRole.class)
	void aValidAccessTokenOfEveryAuthRoleProvesItsOwnIdentity(StaffRole role) {
		UUID restaurant = UUID.randomUUID();
		UUID user = UUID.randomUUID();

		var verified = verifier.verify(StaffTokens.sign(StaffTokens.claims(role.name(), restaurant, user, NOW))).orElseThrow();

		assertEquals(new KitchenPrincipal(user, restaurant), verified.principal());
		assertEquals(role, verified.role());
		assertEquals(NOW.plusSeconds(300), verified.expiresAt());
	}

	private static Arguments rejected(String name, Consumer<Map<String, Object>> change) {
		return Arguments.of(name, change);
	}

	private static Consumer<Map<String, Object>> set(String claim, Object value) {
		return claims -> claims.put(claim, value);
	}

	private static Consumer<Map<String, Object>> without(String claim) {
		return claims -> claims.remove(claim);
	}

	static Stream<Arguments> rejectedClaims() {
		long now = NOW.getEpochSecond();
		return Stream.of(
				rejected("expired", c -> {
					c.put("iat", now - 400);
					c.put("nbf", now - 400);
					c.put("exp", now - 100);
				}),
				rejected("expiry is exactly now (no leeway)", c -> {
					c.put("iat", now - 300);
					c.put("nbf", now - 300);
					c.put("exp", now);
				}),
				rejected("expired within the clock skew", c -> {
					c.put("iat", now - 301);
					c.put("nbf", now - 301);
					c.put("exp", now - 1);
				}),
				rejected("issued in the future", c -> {
					c.put("iat", now + 60);
					c.put("nbf", now + 60);
					c.put("exp", now + 120);
				}),
				rejected("nbf differs from iat", set("nbf", now - 1)),
				rejected("longer lived than Auth's access lifetime", set("exp", now + 301)),
				rejected("no expiry", without("exp")),
				rejected("no iat", without("iat")),
				rejected("no nbf", without("nbf")),
				rejected("wrong issuer", set("iss", "somebody-else")),
				rejected("no issuer", without("iss")),
				rejected("wrong audience", set("aud", List.of("restofy-kitchen"))),
				rejected("MFA audience", set("aud", List.of("restofy-auth-mfa"))),
				rejected("second audience", set("aud", List.of("restofy-staff-api", "restofy-kitchen"))),
				rejected("no audience", without("aud")),
				rejected("MFA pending token type", set("token_type", "mfa_pending")),
				rejected("service token type", set("token_type", "service")),
				rejected("refresh token type", set("token_type", "refresh")),
				rejected("no token type", without("token_type")),
				rejected("MFA pending state", set("auth_state", "MFA_PENDING")),
				rejected("no auth state", without("auth_state")),
				rejected("old policy", set("auth_policy", "staff-v1")),
				rejected("no policy", without("auth_policy")),
				rejected("unknown role", set("role", "PLATFORM_ADMIN")),
				rejected("role with other case", set("role", "kitchen")),
				rejected("role as a list", set("role", List.of("KITCHEN"))),
				rejected("no role", without("role")),
				rejected("no subject", without("sub")),
				rejected("subject is not a UUID", set("sub", "x".repeat(36))),
				rejected("shortened UUID", set("sub", "1-1-1-1-1")),
				rejected("no session id", without("sid")),
				rejected("no token id", without("jti")),
				rejected("no restaurant", without("restaurant_id")),
				rejected("restaurant is not a UUID", set("restaurant_id", "my-restaurant")),
				rejected("zero restaurant", set("restaurant_id", new UUID(0, 0).toString())));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("rejectedClaims")
	void aSignedTokenWithAWrongOrMissingClaimIsNotAStaffToken(String name, Consumer<Map<String, Object>> change) {
		assertTrue(verifier.verify(StaffTokens.altered(NOW, change)).isEmpty(), name);
	}

	@Test
	void theUnalteredFixtureIsAcceptedSoEveryRejectionAboveIsCausedByItsChange() {
		assertTrue(verifier.verify(StaffTokens.altered(NOW, claims -> { })).isPresent());
	}

	@Test
	void aFewSecondsOfClockDifferenceAtTheStartAreTolerated() {
		assertTrue(verifier.verify(StaffTokens.altered(NOW.plusSeconds(3), claims -> { })).isPresent());
		assertTrue(verifier.verify(StaffTokens.altered(NOW.plusSeconds(4), claims -> { })).isEmpty());
	}

	/** Auth shortens a token when the session ends sooner: "at most 300 seconds", not "exactly". */
	@ParameterizedTest
	@ValueSource(longs = { 1, 30, 299, 300 })
	void everyLifetimeUpToTheBoundIsAccepted(long lifetime) {
		var token = StaffTokens.altered(NOW, c -> c.put("exp", NOW.getEpochSecond() + lifetime));

		assertEquals(NOW.plusSeconds(lifetime), verifier.verify(token).orElseThrow().expiresAt());
	}

	@ParameterizedTest
	@ValueSource(longs = { 301, 900, 3600 })
	void aLifetimeAboveTheBoundIsRejected(long lifetime) {
		assertTrue(verifier.verify(StaffTokens.altered(NOW, c -> c.put("exp", NOW.getEpochSecond() + lifetime))).isEmpty());
	}

	/** Reads rely on this check alone, so the very token must stop working at its expiry, without any leeway. */
	@Test
	void aTokenStopsBeingAcceptedTheMomentItExpires() {
		var token = StaffTokens.altered(NOW, c -> c.put("exp", NOW.getEpochSecond() + 60));

		assertTrue(verifierAt(NOW.plusSeconds(59)).verify(token).isPresent());
		assertTrue(verifierAt(NOW.plusSeconds(60)).verify(token).isEmpty());
		assertTrue(verifierAt(NOW.plusSeconds(61)).verify(token).isEmpty());
	}

	private static StaffTokenVerifier verifierAt(Instant moment) {
		return new StaffTokenVerifier(properties(StaffTokens.publicKey()), Clock.fixed(moment, ZoneOffset.UTC));
	}

	@Test
	void aTokenSignedWithAnotherKeyIsRejected() {
		var claims = StaffTokens.claims("KITCHEN", UUID.randomUUID(), UUID.randomUUID(), NOW);

		assertTrue(verifier.verify(StaffTokens.sign(claims, StaffTokens.generate().getPrivate())).isEmpty());
	}

	@Test
	void onlyRs256IsAcceptedEvenWithTheRightKey() {
		var claims = StaffTokens.claims("KITCHEN", UUID.randomUUID(), UUID.randomUUID(), NOW);
		String rs384 = Jwts.builder().claims(claims).signWith(StaffTokens.KEYS.getPrivate(), Jwts.SIG.RS384).compact();
		// The classic confusion attack: the public key used as an HMAC secret.
		String hmac = Jwts.builder().claims(claims)
				.signWith(new SecretKeySpec(StaffTokens.KEYS.getPublic().getEncoded(), "HmacSHA256"), Jwts.SIG.HS256).compact();
		String unsigned = Jwts.builder().claims(claims).compact();

		assertTrue(verifier.verify(rs384).isEmpty(), "RS384");
		assertTrue(verifier.verify(hmac).isEmpty(), "HS256");
		assertTrue(verifier.verify(unsigned).isEmpty(), "alg none");
		assertTrue(verifier.verify(unsigned + "AAAA").isEmpty(), "alg none with a fake signature");
	}

	@Test
	void aChangedPayloadWithTheOriginalSignatureIsRejected() {
		String[] kitchen = StaffTokens.sign(StaffTokens.claims("KITCHEN", UUID.randomUUID(), UUID.randomUUID(), NOW)).split("\\.");
		String payload = new String(Base64.getUrlDecoder().decode(kitchen[1]), StandardCharsets.UTF_8)
				.replace("KITCHEN", "ADMIN");
		String forged = kitchen[0] + "." + Base64.getUrlEncoder().withoutPadding()
				.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + kitchen[2];

		assertTrue(verifier.verify(forged).isEmpty());
	}

	@Test
	void anOversizedOrMalformedValueIsRejected() {
		String oversized = StaffTokens.altered(NOW, set("padding", "p".repeat(4000)));

		assertTrue(oversized.length() > 4096);
		for (String value : new String[] { oversized, "", "abc", "a.b", "a.b.c", "a.b.c.d", "a b.c.d", "x; y=z", null }) {
			assertTrue(verifier.verify(value).isEmpty(), String.valueOf(value));
		}
	}

	@Test
	void withoutAConfiguredPublicKeyNothingVerifies() {
		var disabled = new StaffTokenVerifier(properties(""), CLOCK);

		assertTrue(disabled.verify(StaffTokens.altered(NOW, claims -> { })).isEmpty());
		assertFalse(properties("").staffAuthenticationEnabled());
	}
}

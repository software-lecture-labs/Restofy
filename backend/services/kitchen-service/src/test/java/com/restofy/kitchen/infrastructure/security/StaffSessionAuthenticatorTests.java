package com.restofy.kitchen.infrastructure.security;

import com.restofy.kitchen.infrastructure.config.KitchenAuthProperties;
import com.restofy.kitchen.infrastructure.security.StaffSessionCheck.SessionState;
import com.restofy.kitchen.support.StaffTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Combining the local proof with Auth's current answer: neither alone authenticates anybody. */
class StaffSessionAuthenticatorTests {

	private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
	private static final Instant LATER = NOW.plusSeconds(600);

	private final StaffTokenVerifier tokens = new StaffTokenVerifier(new KitchenAuthProperties(
			"https://auth.example.test", StaffTokens.publicKey(), StaffTokens.ISSUER, 300,
			List.of("http://localhost:5000"), 3000, 5000), CLOCK);

	private final UUID restaurant = UUID.randomUUID();
	private final UUID user = UUID.randomUUID();
	private final String token = StaffTokens.sign(StaffTokens.claims("KITCHEN", restaurant, user, NOW));

	private StaffSessionAuthenticator with(StaffSessionCheck check) {
		return new StaffSessionAuthenticator(tokens, check, CLOCK);
	}

	private static SessionState state(String status, String role, Instant idle, Instant absolute, Boolean setup) {
		return new SessionState(status, role, idle, absolute, setup);
	}

	private static SessionState valid() {
		return state("AUTHENTICATED", "KITCHEN", LATER, LATER, null);
	}

	@Test
	void aVerifiedTokenAndAConfirmedSessionAuthenticateWithTheSignedIdentityOnly() {
		List<String> asked = new ArrayList<>();

		var authentication = with(value -> {
			asked.add(value);
			return Optional.of(valid());
		}).authenticate(token).orElseThrow();

		assertEquals(new KitchenPrincipal(user, restaurant), authentication.getPrincipal());
		assertEquals(List.of("ROLE_KITCHEN"), authentication.getAuthorities().stream().map(Object::toString).toList());
		assertEquals("", authentication.getCredentials(), "the token is not kept after authentication");
		assertEquals(List.of(token), asked, "Auth is asked about the very same token, once");
	}

	@Test
	void anInvalidTokenIsRefusedWithoutAskingAuth() {
		var authenticator = with(value -> {
			throw new AssertionError("Auth must not receive an unverified credential");
		});

		assertTrue(authenticator.authenticate("not.a.token").isEmpty());
		assertTrue(authenticator.authenticate(StaffTokens.altered(NOW, c -> c.put("token_type", "service"))).isEmpty());
	}

	@Test
	void aSessionAuthRefusesDoesNotAuthenticateAlthoughTheTokenIsStillValid() {
		assertTrue(with(value -> Optional.empty()).authenticate(token).isEmpty());
	}

	static Stream<Arguments> untrustworthyAnswers() {
		return Stream.of(
				Arguments.of("another status", state("MFA_PENDING", "KITCHEN", LATER, LATER, null)),
				Arguments.of("no status", state(null, "KITCHEN", LATER, LATER, null)),
				Arguments.of("another role", state("AUTHENTICATED", "ADMIN", LATER, LATER, null)),
				Arguments.of("no role", state("AUTHENTICATED", null, LATER, LATER, null)),
				Arguments.of("no idle expiry", state("AUTHENTICATED", "KITCHEN", null, LATER, null)),
				Arguments.of("no absolute expiry", state("AUTHENTICATED", "KITCHEN", LATER, null, null)),
				Arguments.of("MFA setup pending", state("AUTHENTICATED", "KITCHEN", LATER, LATER, true)));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("untrustworthyAnswers")
	void anAnswerThatDoesNotFitTheTokenIsUnavailableNeverSuccess(String name, SessionState answer) {
		var authenticator = with(value -> Optional.of(answer));

		assertThrows(StaffAuthUnavailableException.class, () -> authenticator.authenticate(token), name);
	}

	@Test
	void aSessionThatIsAlreadyOverIsRefused() {
		assertTrue(with(value -> Optional.of(state("AUTHENTICATED", "KITCHEN", NOW, LATER, false)))
				.authenticate(token).isEmpty(), "idle expired");
		assertTrue(with(value -> Optional.of(state("AUTHENTICATED", "KITCHEN", LATER, NOW, false)))
				.authenticate(token).isEmpty(), "absolute lifetime over");
	}

	@Test
	void aTokenThatExpiresWhileAuthIsAnsweringIsRefused() {
		Instant[] now = { NOW };
		Clock moving = new Clock() {
			@Override
			public ZoneOffset getZone() {
				return ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(java.time.ZoneId zone) {
				return this;
			}

			@Override
			public Instant instant() {
				return now[0];
			}
		};
		var authenticator = new StaffSessionAuthenticator(tokens, value -> {
			now[0] = NOW.plusSeconds(301);
			return Optional.of(state("AUTHENTICATED", "KITCHEN", NOW.plusSeconds(900), NOW.plusSeconds(900), null));
		}, moving);

		assertTrue(authenticator.authenticate(token).isEmpty());
	}

	@Test
	void anUnavailableOrCrashingCheckFailsClosed() {
		var unavailable = with(value -> {
			throw new StaffAuthUnavailableException("status-503");
		});
		var crashing = with(value -> {
			throw new IllegalStateException("unexpected");
		});

		assertEquals("status-503",
				assertThrows(StaffAuthUnavailableException.class, () -> unavailable.authenticate(token)).getReason());
		assertEquals("session-check-failed",
				assertThrows(StaffAuthUnavailableException.class, () -> crashing.authenticate(token)).getReason());
	}
}

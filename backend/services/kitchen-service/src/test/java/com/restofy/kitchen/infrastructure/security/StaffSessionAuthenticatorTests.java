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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The local proof authenticates; Auth's current answer confirms that very proof or the request does not run. */
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

	/** Proves the token locally and asks Auth about exactly that proof. */
	private boolean confirmed(StaffSessionAuthenticator authenticator) {
		return authenticator.confirm(authenticator.prove(token).orElseThrow());
	}

	private static SessionState state(String status, String role, Instant idle, Instant absolute, Boolean setup) {
		return new SessionState(status, role, idle, absolute, setup);
	}

	private static SessionState valid() {
		return state("AUTHENTICATED", "KITCHEN", LATER, LATER, null);
	}

	@Test
	void theLocalProofCarriesTheSignedIdentityOnlyAndAuthIsAskedAboutThatVeryToken() {
		List<String> asked = new ArrayList<>();
		var authenticator = with(value -> {
			asked.add(value);
			return Optional.of(valid());
		});

		var proof = authenticator.prove(token).orElseThrow();
		var authentication = proof.authentication();

		assertEquals(List.of(), asked, "the local proof never calls Auth");
		assertEquals(new KitchenPrincipal(user, restaurant), authentication.getPrincipal());
		assertEquals(List.of("ROLE_KITCHEN"), authentication.getAuthorities().stream().map(Object::toString).toList());
		assertEquals("", authentication.getCredentials(), "the token is not kept in the authentication");
		assertFalse(proof.toString().contains(token), "the proof never shows the token");
		assertTrue(authenticator.confirm(proof));
		assertEquals(List.of(token), asked, "Auth is asked about the very same token, once");
		assertTrue(authenticator.confirm(proof));
		assertEquals(List.of(token, token), asked, "an answer is not remembered");
	}

	@Test
	void anInvalidTokenGivesNoProofAndAuthIsNotAsked() {
		var authenticator = with(value -> {
			throw new AssertionError("Auth must not receive an unverified credential");
		});

		assertTrue(authenticator.prove("not.a.token").isEmpty());
		assertTrue(authenticator.prove(StaffTokens.altered(NOW, c -> c.put("token_type", "service"))).isEmpty());
	}

	@Test
	void aSessionAuthRefusesIsNotConfirmedAlthoughTheTokenIsStillValid() {
		assertFalse(confirmed(with(value -> Optional.empty())));
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

		var proof = authenticator.prove(token).orElseThrow();

		assertThrows(StaffAuthUnavailableException.class, () -> authenticator.confirm(proof), name);
	}

	@Test
	void aSessionThatIsAlreadyOverIsRefused() {
		assertFalse(confirmed(with(value -> Optional.of(state("AUTHENTICATED", "KITCHEN", NOW, LATER, false)))),
				"idle expired");
		assertFalse(confirmed(with(value -> Optional.of(state("AUTHENTICATED", "KITCHEN", LATER, NOW, false)))),
				"absolute lifetime over");
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

		assertFalse(confirmed(authenticator));
	}

	@Test
	void anUnavailableOrCrashingCheckFailsClosed() {
		var unavailable = with(value -> {
			throw new StaffAuthUnavailableException("status-503");
		});
		var crashing = with(value -> {
			throw new IllegalStateException("unexpected");
		});

		var first = unavailable.prove(token).orElseThrow();
		var second = crashing.prove(token).orElseThrow();

		assertEquals("status-503",
				assertThrows(StaffAuthUnavailableException.class, () -> unavailable.confirm(first)).getReason());
		assertEquals("session-check-failed",
				assertThrows(StaffAuthUnavailableException.class, () -> crashing.confirm(second)).getReason());
	}
}

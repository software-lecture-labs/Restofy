package com.restofy.kitchen.infrastructure.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * The two halves of staff authentication. {@link #prove} is the local one ({@link StaffTokenVerifier}) and is what
 * authenticates every staff request; it never calls Auth. {@link #confirm} additionally asks Auth whether the session
 * is valid right now ({@link StaffSessionCheck}); every request that is not a reviewed read needs it as well
 * ({@link StaffSessionConfirmationFilter}). The identity, restaurant and role come from the token Auth signed;
 * nothing is taken from request headers, parameters or bodies.
 *
 * <p>This is the only production code that creates a {@link KitchenAuthentication}.
 */
public class StaffSessionAuthenticator {

	private static final String AUTHENTICATED = "AUTHENTICATED";

	private final StaffTokenVerifier tokens;
	private final StaffSessionCheck sessions;
	private final Clock clock;

	public StaffSessionAuthenticator(StaffTokenVerifier tokens, StaffSessionCheck sessions, Clock clock) {
		this.tokens = tokens;
		this.sessions = sessions;
		this.clock = clock;
	}

	/** Empty for a token that does not verify locally (401). Says nothing about whether the session was revoked. */
	public Optional<StaffProof> prove(String accessToken) {
		return tokens.verify(accessToken).map(verified -> new StaffProof(accessToken, verified));
	}

	/**
	 * Asks Auth about the very token that was proven. {@code false} when Auth refuses the session or it is over (401);
	 * {@link StaffAuthUnavailableException} when Auth cannot answer reliably (503). The answer is used for the current
	 * request only and is not remembered.
	 *
	 * <p>Auth's answer names no user and no restaurant: it is about the session of the token that was sent. Of what it
	 * does say, the status and the role must fit the token.
	 */
	public boolean confirm(StaffProof proof) {
		var session = askAuth(proof.accessToken);
		if (session.isEmpty()) {
			return false;
		}
		var state = session.get();
		// A positive answer that does not fit the token cannot be trusted in either direction.
		if (!AUTHENTICATED.equals(state.status()) || !proof.token.role().name().equals(state.role())
				|| state.idleExpiresAt() == null || state.absoluteExpiresAt() == null
				|| Boolean.TRUE.equals(state.mfaSetupRequired())) {
			throw new StaffAuthUnavailableException("inconsistent-session-answer");
		}
		Instant now = clock.instant();
		// Re-checked after the remote call: the token may have expired while Auth was answering.
		return proof.token.expiresAt().isAfter(now) && state.idleExpiresAt().isAfter(now)
				&& state.absoluteExpiresAt().isAfter(now);
	}

	private Optional<StaffSessionCheck.SessionState> askAuth(String accessToken) {
		try {
			return sessions.current(accessToken);
		} catch (StaffAuthUnavailableException unavailable) {
			throw unavailable;
		} catch (RuntimeException _) {
			// Fail closed: an unexpected failure of the check never lets the request through.
			throw new StaffAuthUnavailableException("session-check-failed");
		}
	}

	/**
	 * A locally proven access token together with what it proves. Only {@link #prove} creates one, so the credential
	 * that is confirmed can never differ from the one that authenticated. It lives no longer than its request and
	 * never shows the token.
	 */
	public static final class StaffProof {

		private final String accessToken;
		private final StaffTokenVerifier.VerifiedStaffToken token;

		private StaffProof(String accessToken, StaffTokenVerifier.VerifiedStaffToken token) {
			this.accessToken = accessToken;
			this.token = token;
		}

		/** The identity the token proves, without any copy of the token. */
		public KitchenAuthentication authentication() {
			return new KitchenAuthentication(token.principal(), token.role());
		}

		@Override
		public String toString() {
			return "StaffProof[" + token + "]";
		}
	}
}

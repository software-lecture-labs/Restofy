package com.restofy.kitchen.infrastructure.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Turns an access cookie into an authenticated staff identity. Both steps must pass: the local proof
 * ({@link StaffTokenVerifier}) and Auth's current answer ({@link StaffSessionCheck}). The identity, restaurant and
 * role come from the token Auth signed; nothing is taken from request headers, parameters or bodies.
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

	/**
	 * Empty for an invalid token or a session Auth refuses (401). {@link StaffAuthUnavailableException} when Auth
	 * cannot answer reliably (503). An invalid token is never sent to Auth.
	 */
	public Optional<KitchenAuthentication> authenticate(String accessToken) {
		var verified = tokens.verify(accessToken);
		if (verified.isEmpty()) {
			return Optional.empty();
		}
		var token = verified.get();
		var session = askAuth(accessToken);
		if (session.isEmpty()) {
			return Optional.empty();
		}
		var state = session.get();
		// A positive answer that does not fit the token cannot be trusted in either direction.
		if (!AUTHENTICATED.equals(state.status()) || !token.role().name().equals(state.role())
				|| state.idleExpiresAt() == null || state.absoluteExpiresAt() == null
				|| Boolean.TRUE.equals(state.mfaSetupRequired())) {
			throw new StaffAuthUnavailableException("inconsistent-session-answer");
		}
		Instant now = clock.instant();
		// Re-checked after the remote call: the token may have expired while Auth was answering.
		if (!token.expiresAt().isAfter(now) || !state.idleExpiresAt().isAfter(now)
				|| !state.absoluteExpiresAt().isAfter(now)) {
			return Optional.empty();
		}
		return Optional.of(new KitchenAuthentication(token.principal(), token.role()));
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
}

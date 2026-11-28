package com.restofy.kitchen.infrastructure.security;

import java.time.Instant;
import java.util.Optional;

/**
 * The remote half of staff authentication: asks Auth whether the session behind an already locally verified access
 * cookie is valid NOW (not logged out, not idle-expired, account active, role unchanged).
 *
 * <p>Contract of an implementation: an empty result means Auth positively refused the session. Anything that keeps
 * Auth from giving a reliable answer throws {@link StaffAuthUnavailableException}; it is never read as "valid" and
 * never as "invalid". Answers are not cached, calls are not retried, and the credential is not logged.
 */
@FunctionalInterface
public interface StaffSessionCheck {

	Optional<SessionState> current(String accessToken);

	/** The fields of Auth's {@code GET /api/v1/auth/session} answer Kitchen relies on. */
	record SessionState(String status, String role, Instant idleExpiresAt, Instant absoluteExpiresAt,
			Boolean mfaSetupRequired) {
	}
}

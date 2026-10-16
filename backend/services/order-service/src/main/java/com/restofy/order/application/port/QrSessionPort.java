package com.restofy.order.application.port;

import java.util.Optional;

/**
 * Contract with the QR Service (session owner). The Order Service never creates or closes sessions.
 *
 * <p>Implementations must be fail-closed: an unknown, revoked or tampered token yields
 * {@link Optional#empty()}; an unreachable QR Service throws a {@code BusinessException} with
 * {@code DEPENDENCY_UNAVAILABLE} instead of ever reporting a session as valid.
 */
public interface QrSessionPort {

    /**
     * Resolves an opaque guest session credential to the main table session + guest session it belongs to,
     * including whether that session is still ACTIVE (not EXPIRED/CLOSED).
     */
    Optional<GuestSessionContext> validateGuestSession(String guestSessionToken);
}

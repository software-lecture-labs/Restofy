package com.restofy.order.application.port;

import java.util.Optional;

/**
 * Contract for verifying a calling SERVICE (Kitchen, Payment) on the internal endpoints. It is separate from
 * {@link AuthContextPort} (staff) and {@link QrSessionPort} (guests) on purpose: a staff credential or guest token can
 * never act as a service, and a service credential can never act as a user.
 *
 * <p>The credential is an opaque string taken from {@code Authorization: Service <credential>}; the Order Service
 * never interprets it. Implementations must be fail-closed: an unknown, expired or tampered credential yields
 * {@link Optional#empty()}, and an unreachable verifier throws a {@code BusinessException} with
 * {@code DEPENDENCY_UNAVAILABLE}, never a successful result.
 *
 * <p>How services really prove their identity (mTLS, signed service tokens, ...) is not decided yet. Until then a MOCK
 * exists in the {@code dev} and {@code test} profiles only, and every other profile gets a stand-in that rejects every
 * call with 503, so the internal endpoints stay closed in production.
 */
public interface ServiceIdentityPort {

    Optional<ServiceActor> resolveService(String credential);
}

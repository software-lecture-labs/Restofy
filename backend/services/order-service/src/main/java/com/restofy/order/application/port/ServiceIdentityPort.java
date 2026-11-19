package com.restofy.order.application.port;

import java.util.Optional;

/**
 * Contract for verifying a calling service on the internal endpoints. It is separate from
 * {@link AuthContextPort} (staff) and {@link QrSessionPort} (guests) on purpose: a staff credential or guest token can
 * never act as a service, and a service credential can never act as a user.
 *
 * <p>The credential is an opaque string taken from {@code Authorization: Service <credential>}; the Order Service
 * never interprets it. Implementations must be fail-closed: an unknown, expired or tampered credential yields
 * {@link Optional#empty()}, and an unreachable verifier throws a {@code BusinessException} with
 * {@code DEPENDENCY_UNAVAILABLE}, never a successful result.
 *
 * <p>The {@code order-qr} profile verifies QR-specific signed request credentials. Kitchen and Payment do not use
 * that trust configuration. The {@code dev} and {@code test} profiles use fixtures. Without either configuration
 * service authentication is unavailable and fails closed.
 */
public interface ServiceIdentityPort {

    Optional<ServiceActor> resolveService(String credential);
}

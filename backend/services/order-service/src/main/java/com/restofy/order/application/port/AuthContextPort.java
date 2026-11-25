package com.restofy.order.application.port;

import java.util.Optional;

/**
 * Contract with the Auth Service for STAFF (ADMIN, WAITER, KITCHEN) authentication. Guests are authenticated through
 * the QR Service ({@link QrSessionPort}) and services through {@code ServiceIdentityPort}; the three are separate on
 * purpose, so a guest session can never act as staff and a staff credential can never act as a service.
 *
 * <p>With the {@code order-auth} profile, the credential is the Auth access JWT from the staff cookie. The adapter
 * verifies the token and confirms the current session with Auth. An unknown, expired, revoked or tampered credential yields
 * {@link Optional#empty()}, and an unreachable Auth Service throws a {@code BusinessException} with
 * {@code DEPENDENCY_UNAVAILABLE}, never a successful result. The returned restaurant is the staff member's ACTIVE
 * restaurant context as verified by Auth.
 *
 * <p>The legacy Staff header is used by the development and test mocks. Profiles without configured Auth
 * authentication get a stand-in that rejects every call with 503.
 */
public interface AuthContextPort {

    Optional<StaffActor> resolveStaff(String credential);
}

package com.restofy.menu.application.security;

/**
 * The port to the guest-session verification that QR owns. Menu hands it the guest credential of a public menu request
 * and gets back the restaurant QR vouches for: that is the only source of the restaurant of a customer read (see
 * ADR-MENU-008 and {@code docs/menu-service/requests/qr-request-01.md}).
 *
 * <p>Contract for every implementation, because Menu turns the answer into HTTP:
 * <ul>
 *   <li>{@link GuestSessionVerification.Verified} only when the trusted source accepted this credential. The restaurant
 *       comes from that answer, never from the request.</li>
 *   <li>{@link GuestSessionVerification.Rejected} only when the source positively refused the credential (Menu answers
 *       the same 404 as for any unverified restaurant).</li>
 *   <li>{@link GuestSessionVerification.Unavailable} for everything else: unreachable, timeout, the source refusing
 *       Menu's service identity, a rate limit, a malformed or contradictory answer (Menu answers 503 and serves nothing).
 *       A failure is never reported as a refusal and never as a success.</li>
 * </ul>
 * An implementation must not log, store or echo the credential, and must not call anything while a Menu database
 * transaction is open: it is called from the web layer before the menu is read.
 *
 * <p>The production implementation is not connected to QR yet (the QR contract and the service identity, D-09, are not
 * decided), so nothing is verified and every public menu request stays a 404.
 */
public interface GuestSessionVerifier {

    GuestSessionVerification verify(GuestToken token);
}

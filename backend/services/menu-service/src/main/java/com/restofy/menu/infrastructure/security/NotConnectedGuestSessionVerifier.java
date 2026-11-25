package com.restofy.menu.infrastructure.security;

import com.restofy.menu.application.security.GuestSessionVerification;
import com.restofy.menu.application.security.GuestSessionVerifier;
import com.restofy.menu.application.security.GuestToken;
import org.springframework.stereotype.Component;

/**
 * Fail-closed default: QR's guest-session verification is not connected, so no credential is ever vouched for and every
 * public menu request is a 404. It is a refusal, not an outage: Menu has nothing to retry. It is replaced by the real
 * QR client once QR and Auth agree on the contract and the service identity (qr-request-01, D-09). Do not make it
 * accept anything: there is no honest way to learn a restaurant from a cookie alone.
 */
@Component
class NotConnectedGuestSessionVerifier implements GuestSessionVerifier {

    @Override
    public GuestSessionVerification verify(GuestToken token) {
        return new GuestSessionVerification.Rejected();
    }
}

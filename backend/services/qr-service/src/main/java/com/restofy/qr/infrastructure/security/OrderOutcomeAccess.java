package com.restofy.qr.infrastructure.security;

import com.restofy.qr.domain.OrderOutcome.*;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Component;

/** Closed until verified Order decisions AND Payment evidence are integrated.
 * A reference, boolean, UI redirect or request header is not evidence. The future
 * adapter must bind identity/tenant/order/event and verify full session coverage.
 * No payment provider, refund, transport or service identity scheme is chosen here.
 */
@Component
public class OrderOutcomeAccess {
    public Evidence verify(Command command) {
        throw new AuthenticationCredentialsNotFoundException("Verified Order and Payment outcomes are unavailable.");
    }
}

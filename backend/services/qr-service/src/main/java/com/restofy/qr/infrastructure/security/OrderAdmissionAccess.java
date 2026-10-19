package com.restofy.qr.infrastructure.security;

import java.util.UUID;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Component;

/** Closed boundary: only a future verified Order identity and durable decisions
 * may reach admission commands. No headers, default roles or guest claim supply it.
 * The adapter must validate tenant, command binding and permanent rejection fencing.
 * No remote calls are allowed here while the parent transaction holds its lock.
 */
@Component
public class OrderAdmissionAccess {
    public UUID requireRestaurantId() {
        throw new AuthenticationCredentialsNotFoundException("Verified Order service context is unavailable.");
    }
}

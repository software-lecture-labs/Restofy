package com.restofy.qr.infrastructure.security;

import com.restofy.qr.domain.SessionClosure.Staff;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The staff actor behind a Waiter closure command. It only reads the locally verified request context set by the
 * Waiter chain (Waiter service credential AND an active, same-tenant WAITER/ADMIN staff delegation). Without
 * that context it stays closed. Body restaurantId/requestedByUserId and arbitrary headers are expectations, never
 * identity proof. It performs no I/O, so it is safe under QR locks.
 */
@Component
public class WaiterLifecycleAccess {
    public Staff requireStaff() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getDetails() instanceof StaffRequestFilter.Principal staff) {
            return new Staff(staff.restaurantId(), staff.userId());
        }
        throw new AuthenticationCredentialsNotFoundException("Verified Waiter and staff delegation are unavailable.");
    }

    /**
     * The tenant of a verified Waiter service credential alone, for read-only status reads that carry no staff
     * delegation. The Waiter chain sets the configured restaurant as principal only after verifying the credential.
     */
    public UUID requireServiceRestaurant() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && authentication.getPrincipal() instanceof UUID restaurant) {
            return restaurant;
        }
        throw new AuthenticationCredentialsNotFoundException("Verified Waiter service credential is unavailable.");
    }
}

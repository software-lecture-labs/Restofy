package com.restofy.qr.infrastructure.security;

import com.restofy.qr.domain.SessionClosure.Staff;
import org.springframework.stereotype.Component;

/** Local verified Auth context only: current active WAITER/ADMIN membership for normal close/status/cleaned.
 * Never infer it from default Security users, browser headers, or guest tokens.
 * The request filter verifies JWT, role and the primary database guard before lifecycle work begins.
 */
@Component
public class SessionLifecycleAccess {
    public Staff requireStaff() {
        var staff = StaffRequestFilter.requireVerified();
        return new Staff(staff.restaurantId(), staff.userId());
    }
}

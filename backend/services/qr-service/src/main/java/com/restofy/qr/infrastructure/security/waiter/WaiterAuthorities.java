package com.restofy.qr.infrastructure.security.waiter;

import java.util.Map;
import java.util.Optional;

/** The scopes the Waiter service may present and the authority each one grants. A token carries exactly one scope. */
final class WaiterAuthorities {
    static final String GUEST_VALIDATE = "WAITER_QR_GUEST_VALIDATE";
    static final String TABLE_READ = "WAITER_QR_TABLE_READ";
    static final String SESSION_CLOSE = "WAITER_QR_SESSION_CLOSE";
    static final String TABLE_CLEAN = "WAITER_QR_TABLE_CLEAN";
    static final String SESSION_CLOSURE_READ = "WAITER_QR_SESSION_CLOSURE_READ";

    static final String ISSUER = "restofy-waiter";
    static final String SUBJECT = "waiter-service";

    private static final Map<String, String> BY_SCOPE = Map.of(
            "qr:guest-validate", GUEST_VALIDATE,
            "qr:table-read", TABLE_READ,
            "qr:session-close", SESSION_CLOSE,
            "qr:table-clean", TABLE_CLEAN,
            "qr:session-closure-read", SESSION_CLOSURE_READ);

    private WaiterAuthorities() { }

    /** The authority granted for a presented scope claim, or empty when Waiter may not present that scope. */
    static Optional<String> forScope(Object scope) {
        return scope instanceof String value ? Optional.ofNullable(BY_SCOPE.get(value)) : Optional.empty();
    }

    /**
     * Only the close and clean commands (POST) also need a delegated, verified staff token (X-Restofy-Staff-Delegation). Their status
     * reads (GET) are read-only, keyed by the command ID and tenant-checked, so the service credential alone opens them:
     * Waiter's background reconciler has no staff request to forward.
     */
    static boolean requiresStaffDelegation(String authority, String method) {
        return "POST".equals(method) && (SESSION_CLOSE.equals(authority) || TABLE_CLEAN.equals(authority));
    }
}

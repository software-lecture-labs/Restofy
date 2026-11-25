package com.restofy.qr.infrastructure.security.waiter;

import java.util.Map;
import java.util.Optional;

/** The scopes the Waiter service may present and the authority each one grants. A token carries exactly one scope. */
final class WaiterAuthorities {
    static final String GUEST_VALIDATE = "WAITER_QR_GUEST_VALIDATE";

    static final String ISSUER = "restofy-waiter";
    static final String SUBJECT = "waiter-service";

    private static final Map<String, String> BY_SCOPE = Map.of("qr:guest-validate", GUEST_VALIDATE);

    private WaiterAuthorities() { }

    /** The authority granted for a presented scope claim, or empty when Waiter may not present that scope. */
    static Optional<String> forScope(Object scope) {
        return scope instanceof String value ? Optional.ofNullable(BY_SCOPE.get(value)) : Optional.empty();
    }
}

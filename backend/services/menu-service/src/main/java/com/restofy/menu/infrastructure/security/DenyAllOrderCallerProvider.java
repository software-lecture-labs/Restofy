package com.restofy.menu.infrastructure.security;

import com.restofy.menu.application.security.TrustedOrderCaller;
import com.restofy.menu.application.security.TrustedOrderCallerProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Fail-closed default: no Order service is ever authenticated, so every catalog lookup is refused as unauthenticated.
 * There is no trusted service identity yet (Auth D-09 is open). Do not turn this into a fixed caller, a header reader
 * or a "trusted network" assumption.
 */
@Component
class DenyAllOrderCallerProvider implements TrustedOrderCallerProvider {

    @Override
    public Optional<TrustedOrderCaller> current() {
        return Optional.empty();
    }
}

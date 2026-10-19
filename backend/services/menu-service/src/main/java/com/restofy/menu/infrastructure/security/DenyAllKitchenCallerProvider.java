package com.restofy.menu.infrastructure.security;

import com.restofy.menu.application.security.TrustedKitchenCaller;
import com.restofy.menu.application.security.TrustedKitchenCallerProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Fail-closed default: no Kitchen service is ever authenticated, so every Kitchen command is refused as
 * unauthenticated. There is no trusted service identity yet (Auth D-09 is open) and no real Kitchen connection exists.
 * Do not turn this into a fixed caller, a header reader or a "trusted network" assumption.
 */
@Component
class DenyAllKitchenCallerProvider implements TrustedKitchenCallerProvider {

    @Override
    public Optional<TrustedKitchenCaller> current() {
        return Optional.empty();
    }
}

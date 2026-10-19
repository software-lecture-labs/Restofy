package com.restofy.menu.support;

import com.restofy.menu.application.security.TrustedKitchenCaller;
import com.restofy.menu.application.security.TrustedKitchenCallerProvider;

import java.util.Optional;
import java.util.UUID;

/**
 * Test-only stand-in for a verified Kitchen service identity (lives in src/test, never packaged). It does not model
 * how the real identity will be verified: that mechanism does not exist yet. Tests set the caller explicitly.
 */
public class TestKitchenCallerProvider implements TrustedKitchenCallerProvider {

    private volatile TrustedKitchenCaller caller;

    public void actAs(UUID restaurantId, UUID onBehalfOfUserId) {
        this.caller = new TrustedKitchenCaller(restaurantId, onBehalfOfUserId);
    }

    public void actAsNobody() {
        this.caller = null;
    }

    @Override
    public Optional<TrustedKitchenCaller> current() {
        return Optional.ofNullable(caller);
    }
}

package com.restofy.menu.support;

import com.restofy.menu.application.security.TrustedOrderCaller;
import com.restofy.menu.application.security.TrustedOrderCallerProvider;

import java.util.Optional;
import java.util.UUID;

/**
 * Test-only stand-in for a verified Order service identity (lives in src/test, never packaged). It does not model how
 * the real identity will be verified: that mechanism does not exist yet. Tests set the caller explicitly.
 */
public class TestOrderCallerProvider implements TrustedOrderCallerProvider {

    private volatile TrustedOrderCaller caller;

    public void actAs(UUID restaurantId) {
        this.caller = new TrustedOrderCaller(restaurantId);
    }

    public void actAsNobody() {
        this.caller = null;
    }

    @Override
    public Optional<TrustedOrderCaller> current() {
        return Optional.ofNullable(caller);
    }
}

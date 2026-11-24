package com.restofy.waiter.infrastructure.security;

import org.springframework.mock.web.MockHttpServletRequest;

import java.util.UUID;

/**
 * Test access to the request attribute that the staff cookie filter sets in production: API tests authenticate
 * through the SecurityContext, so this gives the request the staff credential Waiter would forward to Order.
 */
public final class TestStaffCredentials {

    private TestStaffCredentials() {
    }

    public static void attach(MockHttpServletRequest request, String credential) {
        request.setAttribute(StaffCredentials.ATTRIBUTE, credential);
    }

    public static String credentialOf(UUID restaurantId) {
        return "fake-staff-" + restaurantId;
    }
}

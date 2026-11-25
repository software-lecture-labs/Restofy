package com.restofy.menu.application.security;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The guest credential and the verification outcomes: what they accept, what they refuse and what they never show. */
class GuestSessionTypesTests {

    private static final String VALID = "Ab3_-".repeat(8) + "xyz";

    @Test
    void aWellFormedCredentialIsFortyThreeUrlSafeCharacters() {
        assertEquals(43, VALID.length());
        assertEquals(Optional.of(new GuestToken(VALID)), GuestToken.parse(VALID));
    }

    @Test
    void anythingElseIsNoCredential() {
        for (String raw : new String[] {null, "", " ", "short", "A".repeat(42), "A".repeat(44), "A".repeat(42) + "!",
                "A".repeat(42) + "%", "A".repeat(42) + " ", "A".repeat(42) + "=", UUID.randomUUID().toString(),
                "A".repeat(42) + "\n"}) {
            assertEquals(Optional.empty(), GuestToken.parse(raw), String.valueOf(raw));
            assertThrows(IllegalArgumentException.class, () -> new GuestToken(raw));
        }
    }

    @Test
    void theCredentialNeverShowsItselfInText() {
        GuestToken token = new GuestToken(VALID);
        assertEquals("GuestToken[REDACTED]", token.toString());
        assertFalse(("seen: " + token).contains(VALID));
    }

    @Test
    void aVerifiedRestaurantNeedsARealRestaurantId() {
        assertThrows(IllegalArgumentException.class, () -> new GuestSessionVerification.Verified(null));
        assertThrows(IllegalArgumentException.class, () -> new GuestSessionVerification.Verified(new UUID(0, 0)));
        UUID restaurant = UUID.randomUUID();
        assertEquals(restaurant, new GuestSessionVerification.Verified(restaurant).restaurantId());
    }
}

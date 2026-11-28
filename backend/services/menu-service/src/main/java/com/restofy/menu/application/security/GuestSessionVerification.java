package com.restofy.menu.application.security;

import java.util.UUID;

/**
 * What a {@link GuestSessionVerifier} can say about a guest credential. Three outcomes, never a fourth: a restaurant
 * the trusted source vouched for, a credential it positively refused, or "could not decide".
 */
public sealed interface GuestSessionVerification {

    /** The trusted source accepted the credential for this restaurant. The id is never the zero UUID. */
    record Verified(UUID restaurantId) implements GuestSessionVerification {
        public Verified {
            if (restaurantId == null || restaurantId.equals(new UUID(0, 0))) {
                throw new IllegalArgumentException("A verified restaurant needs a real restaurant id.");
            }
        }
    }

    /** The trusted source positively said the credential is unknown, expired, closed or for another restaurant. */
    record Rejected() implements GuestSessionVerification { }

    /** No decision: the source was unreachable, slow, refused Menu's own identity or answered something unusable. */
    record Unavailable() implements GuestSessionVerification { }
}

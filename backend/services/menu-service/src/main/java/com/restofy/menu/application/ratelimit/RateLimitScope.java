package com.restofy.menu.application.ratelimit;

/**
 * The separate budgets Menu counts. A scope is a risk profile, not a place in the code: its name is the first half of the
 * counter key, so two scopes never share a count even for the same subject, and the second half (the subject) is always
 * something the server derived (a verified restaurant, a verified actor, the TCP peer), never a value the client typed.
 */
public enum RateLimitScope {

    /** The customer menu of a restaurant QR/Admin verification vouched for. Subject: that restaurant. */
    PUBLIC_RESTAURANT,
    /** Customer menu requests nobody vouched for (they all end in the same 404). Subject: the TCP peer. */
    PUBLIC_UNVERIFIED,
    /** Management reads of a verified actor. Subject: restaurant and user. */
    ADMIN_READ,
    /** Management writes of a verified actor. Subject: restaurant and user. */
    ADMIN_WRITE,
    /** Management reads of one restaurant, all its users together. Subject: the restaurant. */
    ADMIN_RESTAURANT_READ,
    /** Management writes of one restaurant, all its users together. Subject: the restaurant. */
    ADMIN_RESTAURANT_WRITE,
    /** Management requests with no verified actor. Subject: the TCP peer. */
    ADMIN_UNVERIFIED,
    /** Calls from another Menu-trusting service for one restaurant. Subject: the verified restaurant of the call. */
    INTERNAL_RESTAURANT
}

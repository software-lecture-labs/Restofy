package com.restofy.menu.application.service;

import com.restofy.menu.domain.enums.AvailabilityStatus;

/**
 * What a command did. {@code replayed} is true when the same command had been accepted before: nothing was applied
 * again and {@code status} / {@code version} are what that first execution produced (a historical answer, not the
 * live state, which may have changed since).
 */
public record KitchenAvailabilityResult(Outcome outcome, boolean replayed, AvailabilityStatus status, long version) {

    public enum Outcome {
        /** The state changed: a new version and a history entry. */
        APPLIED,
        /** Already in the requested state: no new version, no history entry. */
        NO_CHANGE
    }
}

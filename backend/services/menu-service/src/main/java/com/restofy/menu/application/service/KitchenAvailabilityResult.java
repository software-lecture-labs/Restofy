package com.restofy.menu.application.service;

import com.restofy.menu.domain.enums.AvailabilityStatus;
import com.restofy.menu.domain.enums.CommandOutcome;

/**
 * What a command did. {@code replayed} is true when the same command had been accepted before: nothing was applied
 * again and {@code status} / {@code version} are what that first execution produced (a historical answer, not the
 * live state, which may have changed since).
 */
public record KitchenAvailabilityResult(CommandOutcome outcome, boolean replayed, AvailabilityStatus status, long version) {
}

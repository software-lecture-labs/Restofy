package com.restofy.menu.domain.enums;

/** What an accepted command did. A rejected command has no outcome: it leaves no record at all. */
public enum CommandOutcome {
    /** The state changed: a new version and a history entry. */
    APPLIED,
    /** Already in the requested state: no new version, no history entry. */
    NO_CHANGE
}

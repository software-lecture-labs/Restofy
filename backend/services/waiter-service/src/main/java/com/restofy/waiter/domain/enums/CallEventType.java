package com.restofy.waiter.domain.enums;

/** Business change of a call that is audited and synchronized to Notification. */
public enum CallEventType {
    CREATED,
    CLAIMED,
    RESOLVED;

    /** Event name used on the wire to Notification (docs/waiter/integration-contracts.md). */
    public String wireName() {
        return "SERVICE_CALL_" + name();
    }
}

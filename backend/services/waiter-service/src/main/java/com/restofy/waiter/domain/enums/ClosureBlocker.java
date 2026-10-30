package com.restofy.waiter.domain.enums;

import java.util.Optional;

/**
 * A reason a table session may not be closed by the normal close command. The set is closed: Waiter assesses
 * the first group from what QR and Order report, QR names the second group when it refuses a close at the moment
 * of closing. A code that is not in this set is never passed on, so foreign text cannot reach the panel.
 *
 * <p>There is deliberately no "force" or "ignore balance" blocker override anywhere: a manual close is the same
 * command, checked by the same owners, with an optional reason and the audit data of the staff member.
 */
public enum ClosureBlocker {

    // ----- assessed by Waiter (informative) from QR and Order data; QR and Order decide again at closing -----
    /** The table has no live main session, so there is nothing to close. */
    NO_LIVE_SESSION,
    /** A close of this session is already under way (QR reports CLOSING). */
    SESSION_CLOSING,
    /** An order is neither served nor cancelled and kitchen has not marked it ready (prepayment does not matter). */
    ORDER_IN_PREPARATION,
    /** An order is ready but not served yet (prepayment does not matter). */
    ORDER_AWAITING_SERVICE,
    /** Order reports a balance that is still due. */
    UNPAID_BALANCE,
    /** A payment attempt is still running. */
    PAYMENT_IN_PROGRESS,
    /** A payment attempt ended without a certain result (timeout, lost callback). */
    PAYMENT_OUTCOME_UNKNOWN,
    /** A cancellation requires a refund that has not been confirmed as made. */
    REFUND_PENDING,
    /** Order did not report payment facts, or its facts contradict each other: nothing can be verified. */
    SETTLEMENT_UNVERIFIED,
    /** More orders than Waiter assesses in one view; QR and Order still decide at closing. */
    TOO_MANY_ORDERS_TO_ASSESS,

    // ----- visible only to QR/Order at the moment of closing -----
    /** An order acceptance is reserved or in flight for this session and not yet settled. */
    ORDER_ADMISSION_IN_FLIGHT;

    public static Optional<ClosureBlocker> parse(String code) {
        if (code == null) return Optional.empty();
        for (ClosureBlocker blocker : values()) {
            if (blocker.name().equals(code)) return Optional.of(blocker);
        }
        return Optional.empty();
    }
}

package com.restofy.order.domain.closure;

/**
 * Why a table session cannot be closed yet. A {@code DEFINITE} blocker is a known unmet condition (the table stays
 * open); an {@code UNCERTAIN} one means the money or the data is not known well enough to decide, which is never
 * treated as "nothing owed" (ADR-QR-001 K7/K9).
 */
public enum ClosureBlocker {
    /** No order was ever accepted: an empty scope is never "paid", the ordinary idle rule applies instead (K3). */
    NO_ORDERS(Kind.DEFINITE),
    /** An order is not COMPLETED (not prepared, not served): paying alone never closes the table (K2). */
    ORDER_NOT_COMPLETED(Kind.DEFINITE),
    /** An order is completed but its payment is not PAID (not requested, failed, cancelled) or still owes money (K2). */
    PAYMENT_NOT_SETTLED(Kind.DEFINITE),
    /** A cancellation is requested and not decided yet. */
    CANCELLATION_PENDING(Kind.DEFINITE),
    /** A cancellation requires a refund that the Payment Service has not confirmed. */
    REFUND_PENDING(Kind.DEFINITE),
    /** A payment attempt is in flight: its result is not known yet. */
    PAYMENT_IN_PROGRESS(Kind.UNCERTAIN),
    /** A trusted PAID message waits for its PENDING and is not verified yet. */
    PAYMENT_VERIFICATION_PENDING(Kind.UNCERTAIN),
    /** Reported money that could not be applied exists: a human has to reconcile it first. */
    RECONCILIATION_REQUIRED(Kind.UNCERTAIN);

    public enum Kind {
        DEFINITE,
        UNCERTAIN
    }

    private final Kind kind;

    ClosureBlocker(Kind kind) {
        this.kind = kind;
    }

    public boolean isDefinite() {
        return kind == Kind.DEFINITE;
    }
}

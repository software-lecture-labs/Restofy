package com.restofy.qr.domain.enums;

/** Verified financial safety for QR coordination, not a Payment attempt or balance. */
public enum FinancialOutcome { UNVERIFIED, SETTLED, CANCELLATION_CLEAR, RECONCILIATION_REQUIRED }

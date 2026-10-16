package com.restofy.order.application.service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How payment references are kept without any extra table. Every applied payment change writes its references into the
 * (append-only, never cleaned) history row's reason, e.g. {@code attemptId=…;captureId=…;amountMinor=9000;currency=TRY},
 * and every handled collection also leaves a permanent marker row in the inbox keyed by {@link #captureKey}. Both
 * outlive the ordinary inbox retention, so a redelivery months later still cannot cause a second financial effect.
 */
final class PaymentReferences {

    private static final Pattern ATTEMPT = Pattern.compile("attemptId=([0-9a-f-]{36})");

    private PaymentReferences() {
    }

    static String reason(UUID attemptId, UUID captureId, Long amountMinor, String currency) {
        StringBuilder reason = new StringBuilder("attemptId=").append(attemptId);
        if (captureId != null) {
            reason.append(";captureId=").append(captureId);
        }
        if (amountMinor != null) {
            reason.append(";amountMinor=").append(amountMinor).append(";currency=").append(currency);
        }
        return reason.toString();
    }

    /** The attempt a history reason refers to, or null for rows that carry none (for example the initial row). */
    static UUID attemptOf(String reason) {
        if (reason == null) {
            return null;
        }
        Matcher matcher = ATTEMPT.matcher(reason);
        return matcher.find() ? UUID.fromString(matcher.group(1)) : null;
    }

    /**
     * The permanent identity of one collection in the inbox: a name-based UUID derived from the capture id only (not from
     * the order), so the same capture cannot be recorded for two different orders either.
     */
    static UUID captureKey(UUID captureId) {
        return UUID.nameUUIDFromBytes(("payment-capture|" + captureId).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Identity of "this capture was reported for THAT order although it already belongs to another one": the permanent
     * marker that keeps such a case from raising the same reconciliation alert again and again.
     */
    static UUID captureOnOrderKey(UUID captureId, UUID orderId) {
        return UUID.nameUUIDFromBytes(("payment-capture|" + captureId + "|order|" + orderId)
                .getBytes(StandardCharsets.UTF_8));
    }
}

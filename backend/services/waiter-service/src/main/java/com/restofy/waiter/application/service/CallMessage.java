package com.restofy.waiter.application.service;

import com.restofy.waiter.domain.exception.InvalidRequestException;

/**
 * Validation of the guest's free text. It is optional; when present it must carry visible text, stay on one
 * line and fit {@link #MAX_LENGTH} characters (Unicode code points), so it can neither flood the panel nor
 * smuggle control characters into logs or notifications. The stored value is the trimmed text.
 */
public final class CallMessage {

    public static final int MAX_LENGTH = 300;

    private CallMessage() {
    }

    public static String normalize(String raw) {
        if (raw == null) return null;
        String text = raw.strip();
        if (text.codePoints().allMatch(CallMessage::invisible)) {
            throw new InvalidRequestException("message must not be blank");
        }
        if (text.codePointCount(0, text.length()) > MAX_LENGTH) {
            throw new InvalidRequestException("message must be at most " + MAX_LENGTH + " characters");
        }
        if (text.codePoints().anyMatch(Character::isISOControl)) {
            throw new InvalidRequestException("message must not contain control characters");
        }
        return text;
    }

    /** Whitespace, any Unicode space (such as a no-break space) and zero-width format characters show nothing. */
    private static boolean invisible(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
                || Character.getType(codePoint) == Character.FORMAT;
    }
}

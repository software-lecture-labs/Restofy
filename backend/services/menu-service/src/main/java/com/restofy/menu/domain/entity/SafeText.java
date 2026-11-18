package com.restofy.menu.domain.entity;

/**
 * Text that is safe to store and to hand back to a client. PostgreSQL {@code text} cannot hold a NUL
 * character at all (the insert fails with a server error, i.e. HTTP 500 instead of a validation error),
 * and other control characters or a lone UTF-16 surrogate are never meaningful in a product name or
 * note and are silently corrupted by some drivers. They are rejected up front as a 400.
 */
final class SafeText {

    private SafeText() { }

    /**
     * @param allowLineBreaks whether {@code \n}, {@code \r} and {@code \t} are acceptable (descriptions and
     *                        notes may be multi-line; names are collapsed to single spaces beforehand)
     */
    static boolean isSafe(String value, boolean allowLineBreaks) {
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.getType(codePoint) == Character.SURROGATE) {
                return false; // codePointAt only yields a bare surrogate when it is not part of a valid pair
            }
            if (Character.isISOControl(codePoint) && !(allowLineBreaks && isLineBreakOrTab(codePoint))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLineBreakOrTab(int codePoint) {
        return codePoint == '\n' || codePoint == '\r' || codePoint == '\t';
    }
}

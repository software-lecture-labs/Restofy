package com.restofy.menu.domain.entity;

import com.restofy.menu.domain.exception.InvalidProductException;

import java.text.Normalizer;

/**
 * The customer's product-name search text. Only what the client typed is cleaned here (Unicode NFC, whitespace
 * collapsed, trimmed); the case and Turkish-letter folding happens in the database on both sides of the
 * comparison, so the stored name and the search text always go through the very same expression.
 */
public final class ProductSearchTerm {

    public static final int MIN_LENGTH = 2;
    public static final int MAX_LENGTH = 50;

    private ProductSearchTerm() { }

    /**
     * @return the cleaned term, or {@code null} when nothing was asked for (absent or blank): the normal
     *         list is returned then, not an error
     * @throws InvalidProductException when the cleaned term is too short, too long or holds control characters
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = Normalizer.normalize(raw, Normalizer.Form.NFC).replaceAll("[\\p{Z}\\s]+", " ").strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        int length = cleaned.codePointCount(0, cleaned.length());
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw new InvalidProductException(
                    "The search text must be between " + MIN_LENGTH + " and " + MAX_LENGTH + " characters.");
        }
        if (!SafeText.isSafe(cleaned, false)) {
            throw new InvalidProductException("The search text contains characters that are not allowed.");
        }
        return cleaned;
    }
}

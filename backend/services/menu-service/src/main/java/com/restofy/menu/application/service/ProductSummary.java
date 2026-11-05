package com.restofy.menu.application.service;

/**
 * The short description on a customer product card: the description with its whitespace collapsed, at most
 * {@value #MAX_LENGTH} characters (code points) including the ellipsis, cut at a word boundary when it has to be
 * shortened. The full description belongs to the product detail, not to the card.
 */
final class ProductSummary {

    static final int MAX_LENGTH = 140;
    private static final String ELLIPSIS = "…";

    private ProductSummary() { }

    /** {@code null} when there is no description. */
    static String of(String description) {
        if (description == null) {
            return null;
        }
        String text = description.replaceAll("[\\p{Z}\\s]+", " ").strip();
        if (text.isEmpty()) {
            return null;
        }
        if (text.codePointCount(0, text.length()) <= MAX_LENGTH) {
            return text;
        }
        int end = text.offsetByCodePoints(0, MAX_LENGTH - 1);
        String head = text.substring(0, end);
        if (!Character.isWhitespace(text.charAt(end))) {
            int boundary = head.lastIndexOf(' ');
            if (boundary > 0) {
                head = head.substring(0, boundary);
            }
        }
        return stripTrailingSeparators(head) + ELLIPSIS;
    }

    private static String stripTrailingSeparators(String text) {
        int end = text.length();
        while (end > 0 && (Character.isWhitespace(text.charAt(end - 1)) || ",.;:-".indexOf(text.charAt(end - 1)) >= 0)) {
            end--;
        }
        return text.substring(0, end);
    }
}

package com.restofy.menu.application.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProductSummaryTests {

    private static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    @Test
    void noDescriptionMeansNoSummary() {
        assertNull(ProductSummary.of(null));
        assertNull(ProductSummary.of(""));
        assertNull(ProductSummary.of(" \n\t   "));
    }

    @Test
    void aShortDescriptionIsKeptWithCollapsedWhitespace() {
        assertEquals("Fresh bread", ProductSummary.of("  Fresh\n\n   bread \t"));
        assertEquals("a b", ProductSummary.of("a  b"));
    }

    @Test
    void theLimitIsInclusive() {
        String exactly = "x".repeat(140);
        assertEquals(exactly, ProductSummary.of(exactly));
        String over = "x".repeat(141);
        String cut = ProductSummary.of(over);
        assertEquals(140, length(cut));
        assertTrue(cut.endsWith("…"));
    }

    @Test
    void aLongDescriptionIsCutAtAWordBoundaryWithAnEllipsis() {
        String text = ("word ".repeat(60)).strip();
        String summary = ProductSummary.of(text);
        assertTrue(length(summary) <= 140, summary);
        assertTrue(summary.endsWith("word…"), summary);
        assertFalse(summary.contains("  "));
    }

    @Test
    void aCutInsideAWordFallsBackToTheLastSpace() {
        String text = "a".repeat(100) + " " + "b".repeat(100);
        assertEquals("a".repeat(100) + "…", ProductSummary.of(text));
    }

    @Test
    void aSingleVeryLongWordIsCutHard() {
        String summary = ProductSummary.of("x".repeat(500));
        assertEquals(140, length(summary));
    }

    @Test
    void trailingSeparatorsAreDroppedBeforeTheEllipsis() {
        String text = "a".repeat(130) + ", " + "b".repeat(50);
        String summary = ProductSummary.of(text);
        assertTrue(summary.endsWith("a…"), summary);
    }

    @Test
    void neverSplitsASurrogatePair() {
        String text = "🍅".repeat(300); // a tomato emoji, two UTF-16 units each
        String summary = ProductSummary.of(text);
        assertEquals(140, length(summary));
        assertTrue(summary.endsWith("…"));
        assertFalse(Character.isLowSurrogate(summary.charAt(summary.length() - 2)) && !Character.isHighSurrogate(summary.charAt(summary.length() - 3)));
        for (int i = 0; i < summary.length(); i++) {
            char c = summary.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertTrue(i + 1 < summary.length() && Character.isLowSurrogate(summary.charAt(i + 1)), "broken pair at " + i);
            }
        }
    }

    @Test
    void turkishTextIsKeptIntact() {
        String text = "Çıtır çıtır ızgara köfte, közlenmiş biber ve şeftali ile; ".repeat(10);
        String summary = ProductSummary.of(text);
        assertTrue(length(summary) <= 140);
        assertTrue(summary.startsWith("Çıtır çıtır ızgara köfte"));
    }
}

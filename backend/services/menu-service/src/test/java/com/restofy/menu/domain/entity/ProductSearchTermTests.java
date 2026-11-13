package com.restofy.menu.domain.entity;

import com.restofy.menu.domain.exception.InvalidProductException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ProductSearchTermTests {

    @Test
    void nothingAskedForMeansNoFilter() {
        assertNull(ProductSearchTerm.normalize(null));
        assertNull(ProductSearchTerm.normalize(""));
        assertNull(ProductSearchTerm.normalize("   "));
        assertNull(ProductSearchTerm.normalize("\t\n  "));
    }

    @Test
    void theTermIsTrimmedAndWhitespaceCollapsed() {
        assertEquals("adana kebap", ProductSearchTerm.normalize("  adana \t\n  kebap  "));
        assertEquals("ab", ProductSearchTerm.normalize(" ab "));
    }

    @Test
    void theLengthIsCountedAfterCleaningInCharactersNotUtf16Units() {
        assertEquals("ab", ProductSearchTerm.normalize("ab"));
        assertEquals("a".repeat(50), ProductSearchTerm.normalize("a".repeat(50)));
        assertThrows(InvalidProductException.class, () -> ProductSearchTerm.normalize("a"));
        assertThrows(InvalidProductException.class, () -> ProductSearchTerm.normalize(" a "));
        String fiftyOne = "a".repeat(51);
        assertThrows(InvalidProductException.class, () -> ProductSearchTerm.normalize(fiftyOne));
        String fiftyEmoji = "🍅".repeat(50);
        assertEquals(fiftyEmoji, ProductSearchTerm.normalize(fiftyEmoji));
        String tooManyEmoji = "🍅".repeat(51);
        assertThrows(InvalidProductException.class, () -> ProductSearchTerm.normalize(tooManyEmoji));
    }

    @Test
    void decomposedTextIsComposed() {
        assertEquals("Çay", ProductSearchTerm.normalize("Çay"));
        assertEquals("İs", ProductSearchTerm.normalize("İs"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\u0000b", "\u0000\u0000", "a\u0001b", "a\u007fb", "a\u0085b", "a\ud800b", "ab\udc00cd"})
    void controlCharactersAndBrokenUnicodeAreRefused(String raw) {
        assertThrows(InvalidProductException.class, () -> ProductSearchTerm.normalize(raw));
    }

    @Test
    void theMessagesAreFixedAndNeverEchoTheValue() {
        var tooShort = assertThrows(InvalidProductException.class, () -> ProductSearchTerm.normalize("q"));
        assertFalse(tooShort.getMessage().contains("q "));
        var unsafe = assertThrows(InvalidProductException.class, () -> ProductSearchTerm.normalize("secret\u0000value"));
        assertFalse(unsafe.getMessage().contains("secret"));
    }
}

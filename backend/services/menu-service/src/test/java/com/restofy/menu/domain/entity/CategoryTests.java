package com.restofy.menu.domain.entity;

import com.restofy.menu.domain.exception.InvalidCategoryException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Name rules of a category: the existing MENU-01 behaviour is unchanged, unsafe characters are now a 400. */
class CategoryTests {

    @Test
    void existingNameRulesAreUnchanged() {
        assertEquals("Main Dishes", Category.normalizeName("  Main \t Dishes\n"));
        assertEquals("Main Dishes", Category.normalizeName("Main  Dishes"));
        assertEquals(150, Category.normalizeName("n".repeat(150)).length());
        assertThrows(InvalidCategoryException.class, () -> Category.normalizeName(null));
        assertThrows(InvalidCategoryException.class, () -> Category.normalizeName("  \t "));
        String tooLong = "n".repeat(151);
        assertThrows(InvalidCategoryException.class, () -> Category.normalizeName(tooLong));
    }

    @Test
    void unicodeNamesAreKept() {
        for (String name : new String[] {"Çorbalar", "İçecekler & Şerbetler", "寿司", "Dessert 🍰", "Кофе"}) {
            assertEquals(name, Category.normalizeName(name));
        }
    }

    @Test
    void controlCharactersAndLoneSurrogatesAreRefused() {
        for (String bad : new String[] {"a\u0000b", "a\u0001b", "a\u001fb", "a\u007fb", "a\u0085b", "a\u009fb", "a\ud800b", "\udc00", "ab\ud83d"}) {
            var failure = assertThrows(InvalidCategoryException.class, () -> Category.normalizeName(bad));
            assertEquals("Category name contains characters that are not allowed.", failure.getMessage());
        }
    }

    @Test
    void factoriesApplyTheSameRules() {
        UUID restaurant = UUID.randomUUID();
        assertThrows(InvalidCategoryException.class, () -> Category.root(restaurant, "bad\u0000", 0));
        Category root = Category.root(restaurant, "Drinks", 0);
        UUID rootId = root.getId();
        assertThrows(InvalidCategoryException.class, () -> Category.child(restaurant, rootId, "bad\u0000", 0));
        assertThrows(InvalidCategoryException.class, () -> root.rename("bad\u0000"));
        assertEquals("Drinks", root.getName(), "a refused rename leaves the name unchanged");
    }
}

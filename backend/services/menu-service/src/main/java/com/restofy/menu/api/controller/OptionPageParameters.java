package com.restofy.menu.api.controller;

import com.restofy.menu.domain.enums.OptionType;
import com.restofy.menu.domain.exception.InvalidProductException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Paging contract of the option list: page >= 0, 1 <= size <= {@value #MAX_SIZE}. The order is fixed, never
 * chosen by the client: INCLUDED first, then ADD, then sort order, name (case-insensitive) and id as the final
 * tie-breaker, so every page is repeatable. {@code INCLUDED} sorts before {@code ADD} when the type is ordered
 * descending as text; the tests pin this so a future new type cannot silently reorder the list.
 */
final class OptionPageParameters {

    static final int DEFAULT_SIZE = 50;
    static final int MAX_SIZE = 100;

    private static final Sort ORDER = Sort.by(
            Sort.Order.desc("type"),
            Sort.Order.asc("sortOrder"),
            Sort.Order.asc("name").ignoreCase(),
            Sort.Order.asc("id"));

    private OptionPageParameters() { }

    static Pageable of(int page, int size) {
        if (page < 0) {
            throw new InvalidProductException("page must not be negative.");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new InvalidProductException("size must be between 1 and " + MAX_SIZE + ".");
        }
        return PageRequest.of(page, size, ORDER);
    }

    /** {@code null} (no filter) or one of the two known types, upper-case. */
    static OptionType type(String raw) {
        if (raw == null) {
            return null;
        }
        for (OptionType candidate : OptionType.values()) {
            if (candidate.name().equals(raw)) {
                return candidate;
            }
        }
        throw new InvalidProductException("type must be INCLUDED or ADD.");
    }
}

package com.restofy.menu.api.controller;

import com.restofy.menu.domain.exception.InvalidCategoryException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Map;

/**
 * Paging contract for management lists: page >= 0, 1 <= size <= {@value #MAX_SIZE}, and
 * {@code sort=field[,asc|desc]} where field comes from a fixed allowlist. The primary key is always
 * appended as a tie-breaker so the order is stable across pages.
 */
final class PageParameters {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 100;

    /** API name -> entity property. Client text never reaches the query as a column or property. */
    private static final Map<String, String> SORTABLE = Map.of(
            "sortOrder", "sortOrder",
            "name", "name",
            "createdAt", "createdAt",
            "updatedAt", "updatedAt");

    private PageParameters() { }

    static Pageable of(int page, int size, String sort) {
        if (page < 0) {
            throw new InvalidCategoryException("page must not be negative.");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new InvalidCategoryException("size must be between 1 and " + MAX_SIZE + ".");
        }
        return PageRequest.of(page, size, sort(sort));
    }

    private static Sort sort(String raw) {
        if (raw == null || raw.isBlank()) {
            return Sort.by(Sort.Order.asc("sortOrder"), Sort.Order.asc("id"));
        }
        String[] parts = raw.split(",", -1);
        String property = parts.length >= 1 ? SORTABLE.get(parts[0]) : null;
        if (parts.length > 2 || property == null) {
            throw new InvalidCategoryException("sort must be one of " + SORTABLE.keySet().stream().sorted().toList()
                    + ", optionally followed by ,asc or ,desc.");
        }
        Sort.Direction direction = Sort.Direction.ASC;
        if (parts.length == 2) {
            direction = switch (parts[1]) {
                case "asc" -> Sort.Direction.ASC;
                case "desc" -> Sort.Direction.DESC;
                default -> throw new InvalidCategoryException("sort direction must be asc or desc.");
            };
        }
        return Sort.by(new Sort.Order(direction, property), Sort.Order.asc("id"));
    }
}

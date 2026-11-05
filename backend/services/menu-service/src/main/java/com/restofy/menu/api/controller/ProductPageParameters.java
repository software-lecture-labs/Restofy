package com.restofy.menu.api.controller;

import com.restofy.menu.domain.exception.InvalidProductException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Map;

/**
 * Paging contract for the product management list: page >= 0, 1 <= size <= {@value #MAX_SIZE}, and
 * {@code sort=field[,asc|desc]} where field comes from a fixed allowlist. The primary key is always
 * appended as a tie-breaker so the order is stable across pages. The default is newest first.
 */
final class ProductPageParameters {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 100;
    private static final String CREATED_AT = "createdAt";

    /** API name -> entity property. Client text never reaches the query as a column or property. */
    private static final Map<String, String> SORTABLE = Map.of(
            "name", "name",
            "basePrice", "basePrice",
            CREATED_AT, CREATED_AT,
            "updatedAt", "updatedAt");

    private ProductPageParameters() { }

    static Pageable of(int page, int size, String sort) {
        if (page < 0) {
            throw new InvalidProductException("page must not be negative.");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new InvalidProductException("size must be between 1 and " + MAX_SIZE + ".");
        }
        return PageRequest.of(page, size, sort(sort));
    }

    private static Sort sort(String raw) {
        if (raw == null || raw.isBlank()) {
            return Sort.by(Sort.Order.desc(CREATED_AT), Sort.Order.asc("id"));
        }
        String[] parts = raw.split(",", -1);
        String property = SORTABLE.get(parts[0]);
        if (parts.length > 2 || property == null) {
            throw new InvalidProductException("sort must be one of " + SORTABLE.keySet().stream().sorted().toList()
                    + ", optionally followed by ,asc or ,desc.");
        }
        Sort.Direction direction = Sort.Direction.ASC;
        if (parts.length == 2) {
            direction = switch (parts[1]) {
                case "asc" -> Sort.Direction.ASC;
                case "desc" -> Sort.Direction.DESC;
                default -> throw new InvalidProductException("sort direction must be asc or desc.");
            };
        }
        return Sort.by(new Sort.Order(direction, property), Sort.Order.asc("id"));
    }
}

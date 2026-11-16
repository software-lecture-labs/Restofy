package com.restofy.menu.api.controller;

import com.restofy.menu.application.service.PublicProductSort;
import com.restofy.menu.domain.exception.InvalidProductException;

import java.util.Map;

/**
 * Paging contract of the public product list: page >= 0, 1 <= size <= {@value #MAX_SIZE} (default
 * {@value #DEFAULT_SIZE}) and {@code sort=field[,asc|desc]} from a fixed allowlist. Default {@code name,asc}.
 */
final class PublicProductPageParameters {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 50;

    private static final Map<String, PublicProductSort.Field> SORTABLE = Map.of(
            "name", PublicProductSort.Field.NAME,
            "basePrice", PublicProductSort.Field.BASE_PRICE,
            "createdAt", PublicProductSort.Field.CREATED_AT);

    private PublicProductPageParameters() { }

    static void validate(int page, int size) {
        if (page < 0) {
            throw new InvalidProductException("page must not be negative.");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new InvalidProductException("size must be between 1 and " + MAX_SIZE + ".");
        }
    }

    static PublicProductSort sort(String raw) {
        if (raw == null || raw.isBlank()) {
            return PublicProductSort.DEFAULT;
        }
        String[] parts = raw.split(",", -1);
        PublicProductSort.Field field = SORTABLE.get(parts[0]);
        if (parts.length > 2 || field == null) {
            throw new InvalidProductException("sort must be one of " + SORTABLE.keySet().stream().sorted().toList()
                    + ", optionally followed by ,asc or ,desc.");
        }
        boolean ascending = true;
        if (parts.length == 2) {
            ascending = switch (parts[1]) {
                case "asc" -> true;
                case "desc" -> false;
                default -> throw new InvalidProductException("sort direction must be asc or desc.");
            };
        }
        return new PublicProductSort(field, ascending);
    }
}

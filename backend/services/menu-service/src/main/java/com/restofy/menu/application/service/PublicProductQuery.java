package com.restofy.menu.application.service;

import java.util.UUID;

/**
 * Filters of the public product list. {@code categoryId} must be a main category (it also covers its
 * subcategories), {@code subcategoryId} a subcategory (only its own products); {@code q} is the raw search text.
 */
public record PublicProductQuery(UUID categoryId, UUID subcategoryId, String q) {
}

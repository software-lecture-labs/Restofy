package com.restofy.menu.application.service;

import java.util.UUID;

/**
 * Management list filter. {@code categoryId} may be a root category (its own products and, unless
 * {@code directOnly}, those of all its subcategories) or a subcategory (only its own products).
 * {@code directOnly} only has a meaning together with a category.
 */
public record ProductFilter(UUID categoryId, boolean directOnly, Boolean active) {
}

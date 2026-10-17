package com.restofy.menu.application.service;

import java.util.List;
import java.util.UUID;

/**
 * Customer-facing view of an active category: no restaurant id, version or timestamps.
 * {@code children} is empty for subcategories.
 */
public record ActiveCategoryNode(UUID id, String name, int sortOrder, List<ActiveCategoryNode> children) {
}

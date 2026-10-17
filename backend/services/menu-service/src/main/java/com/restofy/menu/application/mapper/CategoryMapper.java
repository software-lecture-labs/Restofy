package com.restofy.menu.application.mapper;

import com.restofy.menu.api.dto.response.CategoryResponse;
import com.restofy.menu.domain.entity.Category;

/** Entities are never returned from the API; this is the only place that builds the response DTO. */
public final class CategoryMapper {

    private CategoryMapper() { }

    public static CategoryResponse toResponse(Category category) {
        return new CategoryResponse(
                category.getId(),
                category.getParentId(),
                category.getName(),
                category.getSortOrder(),
                category.isActive(),
                category.getVersion() == null ? 0L : category.getVersion(),
                category.getCreatedAt(),
                category.getUpdatedAt());
    }
}

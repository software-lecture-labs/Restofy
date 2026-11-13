package com.restofy.menu.api.dto.request;

import com.restofy.menu.domain.entity.Category;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Partial update guarded by the version the client last saw. Changing the parent is not supported
 * in V1, so a {@code parentId} in the body is a 400 instead of being silently ignored.
 */
public record UpdateCategoryRequest(
        @Size(max = Category.NAME_MAX_LENGTH) String name,
        @Min(0) Integer sortOrder,
        Boolean isActive,
        @NotNull @Min(0) Long version,
        @Null(message = "Changing the parent is not supported.") UUID parentId) {
}

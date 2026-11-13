package com.restofy.menu.api.dto.request;

import com.restofy.menu.domain.entity.Category;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Restaurant is never part of the body (it comes from the verified actor). The parent of a
 * subcategory is the path variable, so a body {@code parentId} is rejected rather than ignored.
 */
public record CreateCategoryRequest(
        @NotBlank @Size(max = Category.NAME_MAX_LENGTH) String name,
        @Min(0) Integer sortOrder,
        @Null(message = "The parent is taken from the URL and cannot be set in the body.") UUID parentId) {
}

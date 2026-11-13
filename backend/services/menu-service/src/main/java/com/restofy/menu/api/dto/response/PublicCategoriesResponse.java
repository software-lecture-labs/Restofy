package com.restofy.menu.api.dto.response;

import java.util.List;

/** The customer category tree. Categories without products are listed too: the restaurant controls them by their active flag. */
public record PublicCategoriesResponse(List<PublicCategoryResponse> categories) {
}

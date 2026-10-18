package com.restofy.menu.api.dto.response;

import java.util.List;
import java.util.UUID;

/** An active main category with its active subcategories, in display order. */
public record PublicCategoryResponse(UUID id, String name, List<PublicCategoryRef> subcategories) {
}

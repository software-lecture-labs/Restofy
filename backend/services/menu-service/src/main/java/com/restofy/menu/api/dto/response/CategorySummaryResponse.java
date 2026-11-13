package com.restofy.menu.api.dto.response;

import java.util.UUID;

/** The category of a product (or its parent), reduced to what a management screen needs. */
public record CategorySummaryResponse(UUID id, String name, boolean isActive) {
}

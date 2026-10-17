package com.restofy.menu.api.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Publish ({@code true}) or hide ({@code false}) a product; guarded by the PRODUCT version the client
 * last saw. Publication is independent of archiving, the category chain and availability.
 */
public record ChangeProductPublicationRequest(
        @NotNull Boolean isPublished,
        @NotNull @Min(0) Long version) {
}

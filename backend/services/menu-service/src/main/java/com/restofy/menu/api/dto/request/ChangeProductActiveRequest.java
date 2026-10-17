package com.restofy.menu.api.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Archive ({@code false}) or restore ({@code true}) a product; guarded by the last seen version. */
public record ChangeProductActiveRequest(
        @NotNull Boolean isActive,
        @NotNull @Min(0) @JsonDeserialize(using = StrictNumberDeserializers.WholeNumber.class) Long version) {
}

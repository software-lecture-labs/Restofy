package com.restofy.menu.api.dto.request;

import com.restofy.menu.domain.entity.ProductOption;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Partial update guarded by the version of the OPTION the client last saw (independent of the product version).
 * A {@code null} field means "leave unchanged". {@code isActive=false} disables the option, {@code true} re-enables
 * it; nothing is ever deleted. The type can never change, so sending it is a 400.
 */
public record UpdateProductOptionRequest(
        @Size(max = ProductOption.NAME_MAX_LENGTH) @JsonDeserialize(using = StrictStringDeserializer.class) String name,
        @DecimalMin("0") @Digits(integer = 15, fraction = 4) @JsonDeserialize(using = StrictNumberDeserializers.Decimal.class) BigDecimal priceDelta,
        @JsonDeserialize(using = StrictBooleanDeserializer.class) Boolean removable,
        @JsonDeserialize(using = StrictNumberDeserializers.WholeNumber.class) Long maxQuantity,
        @Min(0) @Max(Integer.MAX_VALUE) @JsonDeserialize(using = StrictNumberDeserializers.WholeNumber.class) Long sortOrder,
        @JsonDeserialize(using = StrictBooleanDeserializer.class) Boolean isActive,
        @NotNull @Min(0) @JsonDeserialize(using = StrictNumberDeserializers.WholeNumber.class) Long version,
        @Null(message = "The type of an option cannot be changed.") String type,
        @Null(message = "The restaurant is taken from the verified actor.") UUID restaurantId,
        @Null(message = "The product is taken from the URL.") UUID productId) {
}

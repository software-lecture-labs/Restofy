package com.restofy.menu.api.dto.request;

import com.restofy.menu.domain.entity.Product;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Partial update guarded by the version the client last saw. A {@code null} field means "leave
 * unchanged"; a blank description clears it. Archiving, publication and availability are separate
 * state axes and are not changed here: sending them (or the restaurant) is a 400.
 */
public record UpdateProductRequest(
        UUID categoryId,
        @Size(max = Product.NAME_MAX_LENGTH) String name,
        @Size(max = Product.DESCRIPTION_MAX_LENGTH) String description,
        @Size(max = Product.INGREDIENTS_TEXT_MAX_LENGTH) @JsonDeserialize(using = StrictStringDeserializer.class) String ingredientsText,
        @DecimalMin("0") @Digits(integer = 15, fraction = 4) @JsonDeserialize(using = StrictNumberDeserializers.Decimal.class) BigDecimal basePrice,
        @Pattern(regexp = "[A-Z]{3}", message = "must be a three-letter uppercase currency code") String currency,
        @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 4) @JsonDeserialize(using = StrictNumberDeserializers.Decimal.class) BigDecimal vatRate,
        @JsonDeserialize(using = StrictBooleanDeserializer.class) Boolean priceIncludesVat,
        @NotNull @Min(0) @JsonDeserialize(using = StrictNumberDeserializers.WholeNumber.class) Long version,
        @Null(message = "The restaurant is taken from the verified actor.") UUID restaurantId,
        @Null(message = "Use the dedicated active endpoint to archive or restore a product.") Boolean isActive,
        @Null(message = "Publication is not changed through a product update.") Boolean isPublished) {
}

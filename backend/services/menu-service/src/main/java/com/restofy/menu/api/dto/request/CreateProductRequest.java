package com.restofy.menu.api.dto.request;

import com.restofy.menu.domain.entity.Product;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Restaurant, actor, state flags and audit values are never part of the body: the restaurant comes
 * from the verified actor and a new product is always an active, unpublished draft. Those fields
 * are rejected (400) instead of silently ignored. Price and VAT are exact decimals; the VAT
 * inclusion flag has no default because it must be an explicit statement. Deeper policy checks
 * (zero price, currency allowlist) are completed in TASK 2.4.
 */
public record CreateProductRequest(
        @NotNull UUID categoryId,
        @NotBlank @Size(max = Product.NAME_MAX_LENGTH) String name,
        @Size(max = Product.DESCRIPTION_MAX_LENGTH) String description,
        @Size(max = Product.INGREDIENTS_TEXT_MAX_LENGTH) @JsonDeserialize(using = StrictStringDeserializer.class) String ingredientsText,
        @Null(message = "Set the image with a product update.") String imageUrl,
        @JsonDeserialize(using = NutritionDeserializers.WholeNumber.class) Integer calories,
        @JsonDeserialize(using = NutritionDeserializers.Decimal.class) BigDecimal weightGrams,
        @JsonDeserialize(using = NutritionDeserializers.WholeNumber.class) Integer estimatedPrepMinutes,
        @NotNull @DecimalMin("0") @Digits(integer = 15, fraction = 4) @JsonDeserialize(using = StrictNumberDeserializers.Decimal.class) BigDecimal basePrice,
        @NotNull @Pattern(regexp = "[A-Z]{3}", message = "must be a three-letter uppercase currency code") String currency,
        @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 4) @JsonDeserialize(using = StrictNumberDeserializers.Decimal.class) BigDecimal vatRate,
        @NotNull @JsonDeserialize(using = StrictBooleanDeserializer.class) Boolean priceIncludesVat,
        @Null(message = "The restaurant is taken from the verified actor.") UUID restaurantId,
        @Null(message = "A new product is always an unpublished draft.") Boolean isPublished,
        @Null(message = "A new product is always active.") Boolean isActive) {
}

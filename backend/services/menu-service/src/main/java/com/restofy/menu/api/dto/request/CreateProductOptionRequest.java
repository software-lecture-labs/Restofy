package com.restofy.menu.api.dto.request;

import com.restofy.menu.domain.entity.ProductOption;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Creates one INCLUDED or ADD option of the product in the URL. {@code type} is a plain upper-case word (never an
 * enum ordinal). Which of the other fields belong to which type is decided by the domain and answered with a
 * 400: INCLUDED needs {@code removable}; ADD needs {@code priceDelta} and {@code maxQuantity}. The product
 * comes from the path and the restaurant from the verified actor, so neither may appear in the body.
 */
public record CreateProductOptionRequest(
        @NotNull @Pattern(regexp = "INCLUDED|ADD", message = "must be INCLUDED or ADD")
        @JsonDeserialize(using = StrictStringDeserializer.class) String type,
        @NotBlank @Size(max = ProductOption.NAME_MAX_LENGTH) @JsonDeserialize(using = StrictStringDeserializer.class) String name,
        @JsonDeserialize(using = StrictBooleanDeserializer.class) Boolean removable,
        @DecimalMin("0") @Digits(integer = 15, fraction = 4) @JsonDeserialize(using = StrictNumberDeserializers.Decimal.class) BigDecimal priceDelta,
        @JsonDeserialize(using = StrictNumberDeserializers.WholeNumber.class) Long maxQuantity,
        @Min(0) @Max(Integer.MAX_VALUE) @JsonDeserialize(using = StrictNumberDeserializers.WholeNumber.class) Long sortOrder,
        @Null(message = "The restaurant is taken from the verified actor.") UUID restaurantId,
        @Null(message = "The product is taken from the URL.") UUID productId) {
}

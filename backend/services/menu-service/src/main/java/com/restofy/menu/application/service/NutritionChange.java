package com.restofy.menu.application.service;

import java.math.BigDecimal;

/**
 * Optional nutrition and preparation facts of a partial product update. Each component is a
 * {@link FieldChange}: {@code null} = not sent (kept), clear = explicit {@code null}, set = new value.
 */
public record NutritionChange(FieldChange<Integer> calories, FieldChange<BigDecimal> weightGrams,
        FieldChange<Integer> estimatedPrepMinutes) {

    public static final NutritionChange NONE = new NutritionChange(null, null, null);

    boolean isEmpty() {
        return calories == null && weightGrams == null && estimatedPrepMinutes == null;
    }
}

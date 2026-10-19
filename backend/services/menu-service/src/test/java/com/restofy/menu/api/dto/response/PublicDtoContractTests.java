package com.restofy.menu.api.dto.response;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The public DTOs are an allowlist of their own: they are not the management DTOs, and a field added to one by
 * accident (restaurant id, version, audit values, state flags, availability reasons) fails here.
 */
class PublicDtoContractTests {

    private static Set<String> components(Class<?> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).collect(Collectors.toSet());
    }

    @Test
    void theProductCardIsExactlyTheCardFields() {
        assertEquals(Set.of("id", "name", "summary", "imageUrl", "basePrice", "currency", "priceIncludesVat", "category", "subcategory",
                "availability", "canOrder"), components(PublicProductCardResponse.class));
    }

    @Test
    void theCategoryDtosAreExactlyTheirFields() {
        assertEquals(Set.of("id", "name"), components(PublicCategoryRef.class));
        assertEquals(Set.of("id", "name", "subcategories"), components(PublicCategoryResponse.class));
        assertEquals(Set.of("categories"), components(PublicCategoriesResponse.class));
    }

    @Test
    void theProductDetailIsExactlyTheDetailFields() {
        assertEquals(Set.of("id", "name", "description", "ingredientsText", "imageUrl", "category", "subcategory", "basePrice", "currency",
                "priceIncludesVat", "calories", "weightGrams", "estimatedPrepMinutes", "availability", "canOrder", "includedIngredients",
                "extras", "rating"), components(PublicProductDetailResponse.class));
        assertEquals(Set.of("id", "name", "removable"), components(PublicProductDetailResponse.IncludedIngredient.class));
        assertEquals(Set.of("id", "name", "priceDelta", "maxQuantity"), components(PublicProductDetailResponse.Extra.class));
        assertEquals(Set.of("status", "averageRating", "ratingCount"), components(PublicProductDetailResponse.Rating.class));
    }

    @Test
    void noManagementFieldIsEverInAPublicDto() {
        List<String> forbidden = List.of("restaurantId", "version", "createdAt", "updatedAt", "createdByUserId", "updatedByUserId", "isActive",
                "isPublished", "isVisibleToCustomer", "reasonCode", "reasonText", "changedAt", "vatRate", "categoryId", "parentCategory",
                "categoryEffectivelyActive", "commandId", "sortOrder", "minQuantity");
        for (Class<?> type : List.of(PublicProductCardResponse.class, PublicCategoryRef.class, PublicCategoryResponse.class,
                PublicCategoriesResponse.class, PublicProductDetailResponse.class, PublicProductDetailResponse.IncludedIngredient.class,
                PublicProductDetailResponse.Extra.class, PublicProductDetailResponse.Rating.class)) {
            Set<String> names = components(type);
            forbidden.forEach(name -> assertFalse(names.contains(name), type.getSimpleName() + " must not expose " + name));
        }
    }

    @Test
    void thePublicCardIsNotTheManagementResponse() {
        assertNotEquals(ProductResponse.class, PublicProductCardResponse.class);
        assertTrue(components(ProductResponse.class).size() > components(PublicProductCardResponse.class).size());
    }
}

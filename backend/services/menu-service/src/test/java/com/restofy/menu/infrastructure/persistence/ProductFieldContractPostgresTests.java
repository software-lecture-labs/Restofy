package com.restofy.menu.infrastructure.persistence;

import com.restofy.menu.api.dto.request.CreateProductRequest;
import com.restofy.menu.api.dto.request.UpdateProductRequest;
import com.restofy.menu.domain.entity.Product;
import jakarta.persistence.Column;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The product field contract, checked layer against layer: the request DTO bounds, the JPA column
 * declarations and the real PostgreSQL columns created by Flyway must describe the same limits.
 */
@SpringBootTest
class ProductFieldContractPostgresTests {

    @Autowired JdbcTemplate jdbc;

    private Map<String, Object> column(String name) {
        return jdbc.queryForMap("""
                SELECT data_type, character_maximum_length, numeric_precision, numeric_scale, is_nullable, column_default
                FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'products' AND column_name = ?""", name);
    }

    /** Bean Validation annotations are carried by the field the record component generates. */
    private static Field component(Class<?> dto, String name) {
        try {
            return dto.getDeclaredField(name);
        } catch (NoSuchFieldException missing) {
            throw new AssertionError("no such DTO field " + name, missing);
        }
    }

    private static Column jpa(String field) throws Exception {
        return Product.class.getDeclaredField(field).getAnnotation(Column.class);
    }

    @Test
    void nameColumnIsVarchar200NotNullAndTheDtoAndEntityShareTheBound() throws Exception {
        var db = column("name");
        assertEquals("character varying", db.get("data_type"));
        assertEquals(Product.NAME_MAX_LENGTH, db.get("character_maximum_length"));
        assertEquals("NO", db.get("is_nullable"));
        assertEquals(200, Product.NAME_MAX_LENGTH);
        assertEquals(Product.NAME_MAX_LENGTH, jpa("name").length());
        assertEquals(Product.NAME_MAX_LENGTH, component(CreateProductRequest.class, "name").getAnnotation(Size.class).max());
        assertEquals(Product.NAME_MAX_LENGTH, component(UpdateProductRequest.class, "name").getAnnotation(Size.class).max());
    }

    @Test
    void descriptionIsAnOptionalTextColumnWithAnApplicationBound() {
        var db = column("description");
        assertEquals("text", db.get("data_type"));
        assertEquals("YES", db.get("is_nullable"));
        assertEquals(Product.DESCRIPTION_MAX_LENGTH, component(CreateProductRequest.class, "description").getAnnotation(Size.class).max());
        assertEquals(Product.DESCRIPTION_MAX_LENGTH, component(UpdateProductRequest.class, "description").getAnnotation(Size.class).max());
        assertNull(component(CreateProductRequest.class, "description").getAnnotation(NotNull.class), "description is optional");
    }

    @Test
    void ingredientsTextIsAnOptionalVarchar1000AndTheDtoAndEntityShareTheBound() throws Exception {
        var db = column("ingredients_text");
        assertEquals("character varying", db.get("data_type"));
        assertEquals(Product.INGREDIENTS_TEXT_MAX_LENGTH, db.get("character_maximum_length"));
        assertEquals(1000, Product.INGREDIENTS_TEXT_MAX_LENGTH);
        assertEquals("YES", db.get("is_nullable"));
        assertNull(db.get("column_default"), "no invented default content");
        assertEquals(Product.INGREDIENTS_TEXT_MAX_LENGTH, jpa("ingredientsText").length());
        assertEquals(Product.INGREDIENTS_TEXT_MAX_LENGTH, component(CreateProductRequest.class, "ingredientsText").getAnnotation(Size.class).max());
        assertEquals(Product.INGREDIENTS_TEXT_MAX_LENGTH, component(UpdateProductRequest.class, "ingredientsText").getAnnotation(Size.class).max());
        assertNull(component(CreateProductRequest.class, "ingredientsText").getAnnotation(NotNull.class), "ingredients text is optional");
    }

    @Test
    void imageUrlIsAnOptionalTextColumnWithoutADefaultAndTheDtoBoundsIt() {
        var db = column("image_url");
        assertEquals("text", db.get("data_type"));
        assertEquals("YES", db.get("is_nullable"));
        assertNull(db.get("column_default"), "no invented default image");
        assertEquals(2048, Product.IMAGE_URL_MAX_LENGTH);
        assertEquals(Product.IMAGE_URL_MAX_LENGTH, component(UpdateProductRequest.class, "imageUrl").getAnnotation(Size.class).max());
        assertNull(component(UpdateProductRequest.class, "imageUrl").getAnnotation(NotNull.class), "a partial update may omit it");
        assertNotNull(component(CreateProductRequest.class, "imageUrl").getAnnotation(jakarta.validation.constraints.Null.class),
                "the image is not set on create");
    }

    @Test
    void basePriceIsNumeric19_4NotNullAndTheDtoBoundsMatchIt() throws Exception {
        var db = column("base_price");
        assertEquals("numeric", db.get("data_type"));
        assertEquals(19, db.get("numeric_precision"));
        assertEquals(4, db.get("numeric_scale"));
        assertEquals("NO", db.get("is_nullable"));
        assertEquals(19, jpa("basePrice").precision());
        assertEquals(4, jpa("basePrice").scale());
        for (Class<?> dto : List.of(CreateProductRequest.class, UpdateProductRequest.class)) {
            Digits digits = component(dto, "basePrice").getAnnotation(Digits.class);
            assertEquals(19 - 4, digits.integer(), dto.getSimpleName());
            assertEquals(4, digits.fraction(), dto.getSimpleName());
            DecimalMin min = component(dto, "basePrice").getAnnotation(DecimalMin.class);
            assertEquals("0", min.value());
            assertTrue(min.inclusive(), "a price of 0 is valid");
        }
        assertNotNull(component(CreateProductRequest.class, "basePrice").getAnnotation(NotNull.class));
        assertNull(component(UpdateProductRequest.class, "basePrice").getAnnotation(NotNull.class), "a partial update may omit it");
    }

    @Test
    void vatRateIsNumeric7_4NotNullAndTheDtoRangeIsZeroToOneHundredInclusive() throws Exception {
        var db = column("vat_rate");
        assertEquals("numeric", db.get("data_type"));
        assertEquals(7, db.get("numeric_precision"));
        assertEquals(4, db.get("numeric_scale"));
        assertEquals("NO", db.get("is_nullable"));
        assertEquals(7, jpa("vatRate").precision());
        assertEquals(4, jpa("vatRate").scale());
        for (Class<?> dto : List.of(CreateProductRequest.class, UpdateProductRequest.class)) {
            var rate = component(dto, "vatRate");
            assertEquals(3, rate.getAnnotation(Digits.class).integer());
            assertEquals(4, rate.getAnnotation(Digits.class).fraction());
            assertTrue(rate.getAnnotation(DecimalMin.class).inclusive());
            assertTrue(rate.getAnnotation(DecimalMax.class).inclusive());
            assertEquals("0", rate.getAnnotation(DecimalMin.class).value());
            assertEquals("100", rate.getAnnotation(DecimalMax.class).value());
        }
        assertNotNull(component(CreateProductRequest.class, "vatRate").getAnnotation(NotNull.class));
    }

    @Test
    void currencyIsCharThreeNotNull() throws Exception {
        var db = column("currency");
        assertEquals("character", db.get("data_type"));
        assertEquals(3, db.get("character_maximum_length"));
        assertEquals("NO", db.get("is_nullable"));
        assertEquals(3, jpa("currency").length());
        assertNotNull(component(CreateProductRequest.class, "currency").getAnnotation(NotNull.class));
    }

    @Test
    void priceIncludesVatIsAnExplicitNotNullBooleanWithoutADefault() {
        var db = column("price_includes_vat");
        assertEquals("boolean", db.get("data_type"));
        assertEquals("NO", db.get("is_nullable"));
        assertNull(db.get("column_default"), "VAT inclusion must always be stated, never defaulted");
        assertNotNull(component(CreateProductRequest.class, "priceIncludesVat").getAnnotation(NotNull.class));
    }

    @Test
    void stateAxesKeepTheirSafeDefaultsAtTheDatabaseLevel() {
        assertTrue(String.valueOf(column("is_active").get("column_default")).contains("true"));
        assertTrue(String.valueOf(column("is_published").get("column_default")).contains("false"), "drafts by default");
    }

    @Test
    void theDatabaseEnforcesTheSameInvariantsByName() {
        List<String> checks = jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'public.products'::regclass AND contype = 'c'", String.class);
        assertTrue(checks.containsAll(List.of("ck_products_name_trimmed", "ck_products_base_price", "ck_products_currency",
                "ck_products_vat_rate", "ck_products_ingredients_text")), checks.toString());
        String price = jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_products_base_price'", String.class);
        assertTrue(price.contains(">= (0)") || price.contains(">= 0"), "zero is allowed, negative is not: " + price);
        String vat = jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_products_vat_rate'", String.class);
        assertTrue(vat.contains(">= (0)") || vat.contains(">= 0"), vat);
        assertTrue(vat.contains("<= (100)") || vat.contains("<= 100"), vat);
    }

    @Test
    void noFloatingPointColumnExistsForMoneyOrTax() {
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns WHERE table_name = 'products'
                AND data_type IN ('double precision', 'real')""", Integer.class));
        for (String field : List.of("basePrice", "vatRate")) {
            assertDoesNotThrow(() -> assertEquals(java.math.BigDecimal.class, Product.class.getDeclaredField(field).getType()));
        }
    }
}

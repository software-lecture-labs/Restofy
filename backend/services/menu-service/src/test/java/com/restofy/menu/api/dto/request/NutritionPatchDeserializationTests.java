package com.restofy.menu.api.dto.request;

import com.restofy.menu.application.service.FieldChange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Jackson 3 behaviour the PATCH contract rests on, proven on the real request records: a missing field, an
 * explicit JSON {@code null} and a value are three different outcomes, and only real JSON numbers are read.
 */
class NutritionPatchDeserializationTests {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static UpdateProductRequest update(String json) {
        return MAPPER.readValue(json, UpdateProductRequest.class);
    }

    private static CreateProductRequest create(String json) {
        return MAPPER.readValue(json, CreateProductRequest.class);
    }

    @Test
    void aMissingFieldIsNullSoTheStoredValueIsKept() {
        var request = update("{\"version\":1}");
        assertNull(request.calories());
        assertNull(request.weightGrams());
        assertNull(request.estimatedPrepMinutes());
    }

    @Test
    void anExplicitJsonNullIsTheClearMarkerNotAMissingField() {
        var request = update("{\"version\":1,\"calories\":null,\"weightGrams\":null,\"estimatedPrepMinutes\":null}");
        assertEquals(FieldChange.clear(), request.calories());
        assertEquals(FieldChange.clear(), request.weightGrams());
        assertEquals(FieldChange.clear(), request.estimatedPrepMinutes());
        assertNull(request.calories().value());
    }

    @Test
    void aNumberIsTheSetMarker() {
        var request = update("{\"version\":1,\"calories\":0,\"weightGrams\":12.345,\"estimatedPrepMinutes\":240}");
        assertEquals(FieldChange.set(0), request.calories());
        assertEquals(0, new BigDecimal("12.345").compareTo(request.weightGrams().value()));
        assertEquals(FieldChange.set(240), request.estimatedPrepMinutes());
    }

    @Test
    void eachFieldIsIndependentOfTheOthers() {
        var request = update("{\"version\":1,\"weightGrams\":null}");
        assertNull(request.calories());
        assertEquals(FieldChange.clear(), request.weightGrams());
        assertNull(request.estimatedPrepMinutes());
    }

    @Test
    void theWeightKeepsTheScaleTheClientWroteSoTheDomainCanRefuseExtraDecimals() {
        assertEquals(4, update("{\"version\":1,\"weightGrams\":12.3450}").weightGrams().value().scale());
        assertEquals(1, update("{\"version\":1,\"weightGrams\":250.0}").weightGrams().value().scale());
    }

    @Test
    void createReadsMissingAndNullAsUnknown() {
        var request = create("{\"calories\":null}");
        assertNull(request.calories());
        assertNull(request.weightGrams());
        assertNull(request.estimatedPrepMinutes());
        var filled = create("{\"calories\":10,\"weightGrams\":2.5,\"estimatedPrepMinutes\":3}");
        assertEquals(10, filled.calories());
        assertEquals(0, new BigDecimal("2.5").compareTo(filled.weightGrams()));
        assertEquals(3, filled.estimatedPrepMinutes());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"250\"", "true", "false", "[1]", "{}", "[]", "250.0", "2.5e2", "1e2", "99999999999", "-99999999999",
            "99999999999999999999999"})
    void aWholeNumberFieldRefusesEverythingButAnIntegerThatFits(String value) {
        assertThrows(JacksonException.class, () -> update("{\"version\":1,\"calories\":" + value + "}"));
        assertThrows(JacksonException.class, () -> update("{\"version\":1,\"estimatedPrepMinutes\":" + value + "}"));
        assertThrows(JacksonException.class, () -> create("{\"calories\":" + value + "}"));
        assertThrows(JacksonException.class, () -> create("{\"estimatedPrepMinutes\":" + value + "}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"250\"", "\"\"", "true", "[1]", "{}", "[]"})
    void theWeightRefusesEverythingButANumber(String value) {
        assertThrows(JacksonException.class, () -> update("{\"version\":1,\"weightGrams\":" + value + "}"));
        assertThrows(JacksonException.class, () -> create("{\"weightGrams\":" + value + "}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"250.0", "1e2", "0.001", "10000", "12.3456"})
    void theWeightReadsEveryJsonNumberFormAndLeavesTheRangeToTheDomain(String value) {
        assertNotNull(update("{\"version\":1,\"weightGrams\":" + value + "}").weightGrams().value());
    }

    @Test
    void negativeAndOutOfRangeIntegersThatFitAnIntAreReadAndJudgedByTheDomain() {
        assertEquals(-1, update("{\"version\":1,\"calories\":-1}").calories().value());
        assertEquals(10001, update("{\"version\":1,\"calories\":10001}").calories().value());
    }
}

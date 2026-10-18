package com.restofy.menu.api.dto.request;

import com.restofy.menu.application.service.FieldChange;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Strict readers for calories, weight and preparation time. Only real JSON numbers are accepted (never a quoted
 * number, boolean, array or object) and a whole-number field refuses a fraction or exponent form
 * ({@code 250.0}, {@code 1e2}) instead of truncating it. The value range is not judged here: that is the
 * product's own rule, so create and update share one source of truth.
 *
 * <p>The {@code *Change} variants are for PATCH. They separate the three states a plain {@code null} cannot:
 * a missing field stays {@code null} (keep), an explicit JSON {@code null} becomes {@link FieldChange#clear()}
 * and a number becomes {@link FieldChange#set}.
 */
public final class NutritionDeserializers {

    private NutritionDeserializers() { }

    private static Integer readWholeNumber(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) {
            BigInteger value = parser.getBigIntegerValue();
            if (value.bitLength() < Integer.SIZE) {
                return value.intValue();
            }
        }
        return (Integer) context.handleUnexpectedToken(Integer.class, parser);
    }

    private static BigDecimal readDecimal(JsonParser parser, DeserializationContext context) {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
            return parser.getDecimalValue();
        }
        return (BigDecimal) context.handleUnexpectedToken(BigDecimal.class, parser);
    }

    /** Create: a whole number; {@code null} (or a missing field) means unknown. */
    public static class WholeNumber extends StdDeserializer<Integer> {

        public WholeNumber() {
            super(Integer.class);
        }

        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) {
            return readWholeNumber(parser, context);
        }
    }

    /** Create: a decimal; {@code null} (or a missing field) means unknown. */
    public static class Decimal extends StdDeserializer<BigDecimal> {

        public Decimal() {
            super(BigDecimal.class);
        }

        @Override
        public BigDecimal deserialize(JsonParser parser, DeserializationContext context) {
            return readDecimal(parser, context);
        }
    }

    /** Update: keep (missing) / clear (explicit null) / set (whole number). */
    public static class WholeNumberChange extends Change<Integer> {

        @Override
        FieldChange<Integer> read(JsonParser parser, DeserializationContext context) {
            return FieldChange.set(readWholeNumber(parser, context));
        }
    }

    /** Update: keep (missing) / clear (explicit null) / set (decimal). */
    public static class DecimalChange extends Change<BigDecimal> {

        @Override
        FieldChange<BigDecimal> read(JsonParser parser, DeserializationContext context) {
            return FieldChange.set(readDecimal(parser, context));
        }
    }

    abstract static class Change<T> extends StdDeserializer<FieldChange<T>> {

        Change() {
            super(FieldChange.class);
        }

        abstract FieldChange<T> read(JsonParser parser, DeserializationContext context);

        @Override
        public FieldChange<T> deserialize(JsonParser parser, DeserializationContext context) {
            return read(parser, context);
        }

        /** An explicit JSON {@code null}: the client asks for the value to be removed. */
        @Override
        public FieldChange<T> getNullValue(DeserializationContext context) {
            return FieldChange.clear();
        }

        /** A missing field must not look like an explicit {@code null}: Jackson would otherwise reuse the clear marker. */
        @Override
        public Object getAbsentValue(DeserializationContext context) {
            return null;
        }
    }
}

package com.restofy.menu.api.dto.request;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;

import java.math.BigDecimal;

/**
 * Number fields of the product API must be real JSON numbers. Jackson would otherwise accept a quoted number
 * ({@code "12.5"}) and also silently truncate a fraction into an integer ({@code "version": 1.5} becoming 1).
 * Money, VAT and the optimistic-lock version are exactly where that ambiguity is unwelcome. Applied per
 * field, so the rest of the API (categories included) keeps its behaviour. {@code null} stays {@code null}
 * and is handled by each field's own {@code @NotNull} or "leave unchanged" meaning.
 */
public final class StrictNumberDeserializers {

    private StrictNumberDeserializers() { }

    /** An exact decimal from a JSON number token only (integer or floating notation), never from a string. */
    public static class Decimal extends StdDeserializer<BigDecimal> {

        public Decimal() {
            super(BigDecimal.class);
        }

        @Override
        public BigDecimal deserialize(JsonParser parser, DeserializationContext context) {
            JsonToken token = parser.currentToken();
            if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
                return parser.getDecimalValue();
            }
            return (BigDecimal) context.handleUnexpectedToken(BigDecimal.class, parser);
        }
    }

    /** A whole number from a JSON integer token only: no string, no fraction that would be cut off. */
    public static class WholeNumber extends StdDeserializer<Long> {

        public WholeNumber() {
            super(Long.class);
        }

        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) {
                return parser.getLongValue();
            }
            return (Long) context.handleUnexpectedToken(Long.class, parser);
        }
    }
}

package com.restofy.menu.api.dto.request;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;

/**
 * Accepts only the JSON literals {@code true} and {@code false}. Jackson would otherwise coerce numbers
 * ({@code 0}, {@code 1}, {@code 2}) into a boolean, which is wrong for a flag that must be an explicit
 * statement (for example "the price includes VAT"). Applied per field, so the rest of the API is unchanged.
 * Anything else (number, string, array, object) is a 400 "not readable" answer; {@code null} stays
 * {@code null} and is handled by the field's own {@code @NotNull} or "leave unchanged" meaning.
 */
public class StrictBooleanDeserializer extends StdDeserializer<Boolean> {

    public StrictBooleanDeserializer() {
        super(Boolean.class);
    }

    @Override
    public Boolean deserialize(JsonParser parser, DeserializationContext context) {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.VALUE_TRUE) {
            return Boolean.TRUE;
        }
        if (token == JsonToken.VALUE_FALSE) {
            return Boolean.FALSE;
        }
        return (Boolean) context.handleUnexpectedToken(Boolean.class, parser);
    }
}

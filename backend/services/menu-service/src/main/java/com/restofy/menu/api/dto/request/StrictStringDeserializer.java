package com.restofy.menu.api.dto.request;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;

/**
 * Accepts only a JSON string. Jackson would otherwise turn a number or a boolean into text ({@code 5} becoming
 * {@code "5"}, {@code true} becoming {@code "true"}), which silently stores something the client never meant as
 * text. Applied per field; {@code null} stays {@code null} and keeps each field's own meaning.
 */
public class StrictStringDeserializer extends StdDeserializer<String> {

    public StrictStringDeserializer() {
        super(String.class);
    }

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() == JsonToken.VALUE_STRING) {
            return parser.getString();
        }
        return (String) context.handleUnexpectedToken(String.class, parser);
    }
}

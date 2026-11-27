package com.restofy.order.infrastructure.security;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

final class SignedServiceKeys {

    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private SignedServiceKeys() {
    }

    static Map<String, RSAPublicKey> read(JsonMapper json, String configured, String property) {
        try {
            Map<String, String> entries = json.readValue(configured, new TypeReference<>() {});
            if (entries.isEmpty()) {
                throw new IllegalArgumentException();
            }
            KeyFactory factory = KeyFactory.getInstance("RSA");
            Map<String, RSAPublicKey> keys = new HashMap<>();
            for (var entry : entries.entrySet()) {
                RSAPublicKey key = (RSAPublicKey) factory.generatePublic(
                        new X509EncodedKeySpec(Base64.getDecoder().decode(entry.getValue())));
                if (key.getModulus().bitLength() < 2048 || !KEY_ID.matcher(entry.getKey()).matches()) {
                    throw new IllegalArgumentException();
                }
                keys.put(entry.getKey(), key);
            }
            return Map.copyOf(keys);
        } catch (Exception ex) {
            throw new IllegalStateException(property + " must contain RSA public keys of at least 2048 bits");
        }
    }
}

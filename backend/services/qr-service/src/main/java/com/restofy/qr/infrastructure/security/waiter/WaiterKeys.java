package com.restofy.qr.infrastructure.security.waiter;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** The public keys Waiter signs with. With no keys configured Waiter is simply not trusted. */
final class WaiterKeys {
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private WaiterKeys() { }

    static Map<String, RSAPublicKey> parse(JsonMapper json, String configuration) {
        if (configuration == null || configuration.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> input = json.readValue(configuration, new TypeReference<>() { });
            if (input.isEmpty()) { throw new IllegalArgumentException(); }
            var factory = KeyFactory.getInstance("RSA");
            Map<String, RSAPublicKey> keys = new HashMap<>();
            for (var entry : input.entrySet()) {
                var key = (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(entry.getValue())));
                if (!KEY_ID.matcher(entry.getKey()).matches() || key.getModulus().bitLength() < 2048) {
                    throw new IllegalArgumentException();
                }
                keys.put(entry.getKey(), key);
            }
            return Map.copyOf(keys);
        } catch (Exception _) {
            throw new IllegalStateException("QR_WAITER_CALLER_PUBLIC_KEYS must map Waiter key IDs to RSA public keys of at least 2048 bits");
        }
    }
}

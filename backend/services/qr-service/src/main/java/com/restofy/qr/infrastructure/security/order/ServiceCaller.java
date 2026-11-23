package com.restofy.qr.infrastructure.security.order;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One trusted internal caller: its token identity, the single scope it may present, the authority a verified call
 * receives and the public keys that belong to it. Keys are never shared between callers.
 */
record ServiceCaller(String issuer, String subject, String scope, String authority, Map<String, RSAPublicKey> keys) {
    static final String ORDER_AUTHORITY = "ORDER_QR_GUEST_SESSION";
    static final String WAITER_AUTHORITY = "WAITER_QR_GUEST_VALIDATE";
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /** Order is the original caller. Its keys are mandatory while the inbound profile is active. */
    static ServiceCaller order(JsonMapper json, String configuration) {
        return new ServiceCaller("restofy-order", "order-service", "qr:guest-session", ORDER_AUTHORITY,
                parse(json, configuration, "QR_ORDER_CALLER_PUBLIC_KEYS", "Order"));
    }

    /** Waiter is optional: with no keys configured it is simply not trusted. */
    static Optional<ServiceCaller> waiter(JsonMapper json, String configuration) {
        if (configuration == null || configuration.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new ServiceCaller("restofy-waiter", "waiter-service", "qr:guest-validate", WAITER_AUTHORITY,
                parse(json, configuration, "QR_WAITER_CALLER_PUBLIC_KEYS", "Waiter")));
    }

    private static Map<String, RSAPublicKey> parse(JsonMapper json, String configuration, String variable, String name) {
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
            throw new IllegalStateException(variable + " must map " + name + " key IDs to RSA public keys of at least 2048 bits");
        }
    }
}

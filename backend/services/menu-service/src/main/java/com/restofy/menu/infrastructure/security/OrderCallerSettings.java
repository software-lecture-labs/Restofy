package com.restofy.menu.infrastructure.security;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What Menu trusts about the Order service, validated once at startup.
 *
 * <p>{@code MENU_ORDER_CALLER_PUBLIC_KEYS}: a JSON object {@code {"<key id>":"<Base64 X.509 RSA public key>"}} (at least 2048
 * bits). Empty (the default) trusts nobody: every catalog request is refused with 401.
 *
 * <p>{@code MENU_ORDER_CALLER_RESTAURANTS}: a JSON object {@code {"<key id>":["<restaurant uuid>", ...]}}, the restaurants a key
 * may read the catalog of. This is the ONLY source of that authorization. A key with no entry may read NO restaurant (every
 * request with it is refused with 403): there is no wildcard and no "all restaurants" default, and a restaurant named in a
 * signed credential is never enough on its own.
 *
 * <p>An invalid setting stops the application at startup; the message names the setting and never shows a value.
 */
final class OrderCallerSettings {

    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Map<String, RSAPublicKey> keys;
    private final Map<String, Set<UUID>> restaurants;

    private OrderCallerSettings(Map<String, RSAPublicKey> keys, Map<String, Set<UUID>> restaurants) {
        this.keys = Map.copyOf(keys);
        this.restaurants = Map.copyOf(restaurants);
    }

    static OrderCallerSettings parse(String publicKeys, String allowedRestaurants) {
        Map<String, RSAPublicKey> keys = new HashMap<>();
        if (publicKeys != null && !publicKeys.isBlank()) {
            JsonNode node = read(publicKeys, "MENU_ORDER_CALLER_PUBLIC_KEYS");
            if (!node.isObject()) {
                throw invalid("MENU_ORDER_CALLER_PUBLIC_KEYS must map key IDs to Base64 X.509 RSA public keys of at least 2048 bits");
            }
            node.propertyNames().forEach(id -> {
                JsonNode value = node.get(id);
                if (!KEY_ID.matcher(id).matches() || !value.isString()) {
                    throw invalid("MENU_ORDER_CALLER_PUBLIC_KEYS must map key IDs to Base64 X.509 RSA public keys of at least 2048 bits");
                }
                keys.put(id, publicKey(value.asString()));
            });
        }
        Map<String, Set<UUID>> restaurants = new HashMap<>();
        if (allowedRestaurants != null && !allowedRestaurants.isBlank()) {
            JsonNode node = read(allowedRestaurants, "MENU_ORDER_CALLER_RESTAURANTS");
            String message = "MENU_ORDER_CALLER_RESTAURANTS must map known key IDs to arrays of restaurant UUIDs";
            if (!node.isObject()) {
                throw invalid(message);
            }
            node.propertyNames().forEach(id -> {
                JsonNode list = node.get(id);
                if (!keys.containsKey(id) || !list.isArray()) {
                    throw invalid(message);
                }
                Set<UUID> allowed = new HashSet<>();
                list.forEach(item -> allowed.add(canonicalUuid(item.isString() ? item.asString() : null, message)));
                restaurants.put(id, allowed);
            });
        }
        return new OrderCallerSettings(keys, restaurants);
    }

    RSAPublicKey key(String keyId) {
        return keyId == null ? null : keys.get(keyId);
    }

    /** The restaurants this key may read; empty (never "all") when none is configured. */
    boolean mayRead(String keyId, UUID restaurantId) {
        return keyId != null && restaurants.getOrDefault(keyId, Set.of()).contains(restaurantId);
    }

    boolean trustsAnyone() {
        return !keys.isEmpty();
    }

    @Override
    public String toString() {
        return "OrderCallerSettings[keys=" + keys.size() + ", restaurantEntries=" + restaurants.size() + "]";
    }

    private static JsonNode read(String text, String setting) {
        try {
            return JSON.readTree(text);
        } catch (RuntimeException e) {
            // Never the parser's message: it can quote the value.
            throw invalid(setting + " must be valid JSON");
        }
    }

    private static RSAPublicKey publicKey(String encoded) {
        try {
            var key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
            if (key instanceof RSAPublicKey rsa && rsa.getModulus().bitLength() >= 2048) {
                return rsa;
            }
        } catch (IllegalArgumentException | GeneralSecurityException _) {
            // Startup errors must never contain the input or the parser's diagnostic.
        }
        throw invalid("MENU_ORDER_CALLER_PUBLIC_KEYS must map key IDs to Base64 X.509 RSA public keys of at least 2048 bits");
    }

    private static UUID canonicalUuid(String value, String message) {
        try {
            if (value != null && value.length() == 36) {
                UUID id = UUID.fromString(value);
                if (id.toString().equals(value)) {
                    return id;
                }
            }
        } catch (IllegalArgumentException _) {
            // Report the setting name only.
        }
        throw invalid(message);
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException(message);
    }
}

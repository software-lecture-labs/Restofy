package com.restofy.qr.infrastructure.security;

import java.net.URI;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Immutable, eagerly validated Q5 trust configuration. Never includes key material in errors/toString. */
@Component
public final class Q5SecuritySettings {
    private static final Set<String> DEVELOPMENT_PROFILES = Set.of("dev", "local", "test");
    private final RSAPublicKey commandKey;
    private final RSAPrivateCrtKey receiptKey;
    private final RSAPublicKey staffKey;
    private final UUID restaurant;
    private final String staffIssuer;
    private final long accessTtl;
    private final List<String> origins;

    public Q5SecuritySettings(Environment environment) {
        try {
            commandKey = publicKey(environment.getRequiredProperty("qr.q5.auth-public-key"));
            staffKey = publicKey(environment.getRequiredProperty("qr.q5.staff-public-key"));
            receiptKey = (RSAPrivateCrtKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(
                    Base64.getDecoder().decode(environment.getRequiredProperty("qr.q5.receipt-private-key"))));
            validateKeys(publicKey(environment.getRequiredProperty("qr.q5.receipt-public-key")));
            restaurant = UUID.fromString(environment.getRequiredProperty("qr.q5.restaurant-id"));
            staffIssuer = environment.getRequiredProperty("qr.q5.staff-issuer");
            if (staffIssuer.isBlank() || !staffIssuer.equals(staffIssuer.strip()) || staffIssuer.length()>200
                    || staffIssuer.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
            accessTtl = Long.parseLong(environment.getRequiredProperty("qr.q5.access-ttl-seconds"));
            if (accessTtl != 300) throw new IllegalArgumentException();
            origins = readOrigins(environment);
        } catch (Exception _) {
            throw new IllegalStateException("Invalid Q5 RSA keys, restaurant, staff issuer, TTL or trusted origins");
        }
    }

    private void validateKeys(RSAPublicKey receiptPublic) {
        var modulus = receiptKey.getModulus();
        if (modulus.bitLength() < 2048 || commandKey.getModulus().equals(staffKey.getModulus())
                || !modulus.equals(receiptPublic.getModulus())
                || !receiptKey.getPublicExponent().equals(receiptPublic.getPublicExponent())
                || modulus.equals(commandKey.getModulus()) || modulus.equals(staffKey.getModulus())) {
            throw new IllegalArgumentException();
        }
    }

    private static List<String> readOrigins(Environment environment) {
        var values = Arrays.stream(environment.getRequiredProperty("qr.q5.allowed-origins").split(",", -1))
                .map(String::strip).toList();
        var profiles = List.of(environment.getActiveProfiles());
        boolean development = !profiles.isEmpty() && DEVELOPMENT_PROFILES.containsAll(profiles)
                && DEVELOPMENT_PROFILES.contains(environment.getProperty("APP_ENV", ""));
        if (values.stream().distinct().count() != values.size()
                || values.stream().anyMatch(origin -> !validOrigin(origin, development))) {
            throw new IllegalArgumentException();
        }
        return values;
    }

    private static boolean validOrigin(String origin, boolean development) {
        URI uri = URI.create(origin);
        if (uri.getHost() == null || origin.contains("*") || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || !uri.getRawPath().isEmpty()) { return false; }
        int port = uri.getPort();
        if (port != -1 && (port < 1 || port > 65535)) { return false; }
        return "https".equals(uri.getScheme()) || (development && "http".equals(uri.getScheme())
                && Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost()));
    }

    private static RSAPublicKey publicKey(String encoded) throws java.security.GeneralSecurityException {
        var key=(RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
        if (key.getModulus().bitLength()<2048) throw new IllegalArgumentException();
        return key;
    }

    public RSAPublicKey commandKey() { return commandKey; }
    public RSAPrivateCrtKey receiptKey() { return receiptKey; }
    public RSAPublicKey staffKey() { return staffKey; }
    public UUID restaurant() { return restaurant; }
    public String staffIssuer() { return staffIssuer; }
    public long accessTtl() { return accessTtl; }
    public List<String> origins() { return origins; }
}

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
            var receiptPublic=publicKey(environment.getRequiredProperty("qr.q5.receipt-public-key"));
            if (receiptKey.getModulus().bitLength()<2048 || commandKey.getModulus().equals(staffKey.getModulus())
                    || !receiptKey.getModulus().equals(receiptPublic.getModulus())
                    || !receiptKey.getPublicExponent().equals(receiptPublic.getPublicExponent())
                    || receiptKey.getModulus().equals(commandKey.getModulus()) || receiptKey.getModulus().equals(staffKey.getModulus())) throw new IllegalArgumentException();
            restaurant = UUID.fromString(environment.getRequiredProperty("qr.q5.restaurant-id"));
            staffIssuer = environment.getRequiredProperty("qr.q5.staff-issuer");
            if (staffIssuer.isBlank() || !staffIssuer.equals(staffIssuer.strip()) || staffIssuer.length()>200
                    || staffIssuer.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
            accessTtl = Long.parseLong(environment.getRequiredProperty("qr.q5.access-ttl-seconds"));
            if (accessTtl<300 || accessTtl>3600) throw new IllegalArgumentException();
            origins = Arrays.stream(environment.getRequiredProperty("qr.q5.allowed-origins").split(",",-1)).map(String::strip).toList();
            boolean development = environment.getActiveProfiles().length>0
                    && Set.of("dev","local","test").containsAll(List.of(environment.getActiveProfiles()))
                    && Set.of("dev","local","test").contains(environment.getProperty("APP_ENV",""));
            for (String origin:origins) {
                URI uri=URI.create(origin);
                boolean local=uri.getHost()!=null && Set.of("localhost","127.0.0.1","[::1]").contains(uri.getHost());
                if (uri.getHost()==null || origin.contains("*") || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null
                        || uri.getRawFragment()!=null || !uri.getRawPath().isEmpty()
                        || (uri.getPort()!=-1 && (uri.getPort()<1 || uri.getPort()>65535))
                        || !("https".equals(uri.getScheme()) || (development && local && "http".equals(uri.getScheme())))) throw new IllegalArgumentException();
            }
            if (origins.stream().distinct().count()!=origins.size()) throw new IllegalArgumentException();
        } catch (Exception invalid) {
            throw new IllegalStateException("Invalid Q5 RSA keys, restaurant, staff issuer, TTL or trusted origins");
        }
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

package com.restofy.auth.infrastructure.security;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import com.restofy.auth.infrastructure.config.AuthSettings;
import com.restofy.auth.infrastructure.config.NotificationTrustSettings;
import com.restofy.auth.support.AuthSessionTestSupport;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.BadCredentialsException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationServiceTokensTests {
    private static final KeyPair KEYS = AuthSessionTestSupport.generateKeys();
    private static final UUID RESTAURANT = UUID.fromString("6c54daf0-9340-4a1c-981d-f9acc0261779");
    private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");
    private static final int TOKEN_LIMIT = 4096;

    @Test
    void verifiesOnlyTheDedicatedCallerKeyAndReadScope() {
        verifier(environment(), NOW).verify("Service " + token(builder -> { }));
        var tokens = verifier(environment(), NOW);
        for (String prefix : new String[]{"Bearer ", "service ", ""}) {
            assertThatThrownBy(() -> tokens.verify(prefix + token(builder -> { })))
                    .isInstanceOf(BadCredentialsException.class);
        }
        assertThatThrownBy(() -> tokens.verify(null)).isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> tokens.verify("Service " + "a".repeat(TOKEN_LIMIT + 1)))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> tokens.verify("Service malformed")).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void rejectsWrongClaimsAndMissingMandatoryTimeOrIdentity() {
        List<Consumer<JwtBuilder>> invalid = List.of(
                builder -> builder.issuer("order-service"), builder -> builder.subject("auth-service"),
                builder -> builder.audience().clear().add("restofy-mail").and(),
                builder -> builder.claim("token_type", "access"), builder -> builder.claim("scope", "staff:write"),
                builder -> builder.claim("restaurant_id", UUID.randomUUID().toString()),
                builder -> builder.issuedAt(null), builder -> builder.notBefore(null), builder -> builder.expiration(null),
                builder -> builder.id(null), builder -> builder.id("not-a-uuid"),
                builder -> builder.issuedAt(Date.from(NOW.plusSeconds(1))),
                builder -> builder.notBefore(Date.from(NOW.plusSeconds(1))),
                builder -> builder.expiration(Date.from(NOW)),
                builder -> builder.expiration(Date.from(NOW.plusSeconds(61))),
                builder -> builder.issuedAt(Date.from(NOW.minusSeconds(90))));
        var tokens = verifier(environment(), NOW);
        for (Consumer<JwtBuilder> mutation : invalid) {
            assertThatThrownBy(() -> tokens.verify("Service " + token(mutation))).isInstanceOf(BadCredentialsException.class);
        }
        assertThatThrownBy(() -> verifier(environment(), NOW.plusSeconds(60)).verify("Service " + token(builder -> { })))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void rejectsDifferentSignatureAndSymmetricAlgorithm() {
        var tokens = verifier(environment(), NOW);
        String wrongKey = builder().signWith(AuthSessionTestSupport.generateKeys().getPrivate(), Jwts.SIG.RS256).compact();
        String symmetric = builder().signWith(Jwts.SIG.HS256.key().build()).compact();
        assertThatThrownBy(() -> tokens.verify("Service " + wrongKey)).isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> tokens.verify("Service " + symmetric)).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void missingKeyFailsClosedAndMalformedKeyFailsStartupWithoutExposingValue() {
        assertThatThrownBy(() -> verifier(new MockEnvironment(), NOW).verify("Service " + token(builder -> { })))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> new NotificationTrustSettings(
                new MockEnvironment().withProperty("AUTH_NOTIFICATION_PUBLIC_KEY", "invalid-public-input")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("AUTH_NOTIFICATION_PUBLIC_KEY")
                .hasMessageNotContaining("invalid-public-input");
        var envFallback = new MockEnvironment().withProperty("AUTH_NOTIFICATION_PUBLIC_KEY",
                Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()));
        verifier(envFallback, NOW).verify("Service " + token(builder -> { }));
    }

    @Test
    void weakOrReusedAuthKeyIsRejectedAtStartup() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        var weak = new MockEnvironment().withProperty("auth.services.notification-public-key",
                Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded()));
        assertThatThrownBy(() -> new NotificationTrustSettings(weak)).isInstanceOf(IllegalStateException.class);
        AuthSettings settings = mock(AuthSettings.class);
        when(settings.publicKey()).thenReturn((RSAPublicKey) KEYS.getPublic());
        assertThatThrownBy(() -> new NotificationServiceTokens(new NotificationTrustSettings(environment()), settings,
                Clock.fixed(NOW, ZoneOffset.UTC))).isInstanceOf(IllegalStateException.class);
    }

    private static MockEnvironment environment() {
        return new MockEnvironment().withProperty("auth.services.notification-public-key",
                Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()));
    }

    private static NotificationServiceTokens verifier(MockEnvironment env, Instant now) {
        AuthSettings settings = mock(AuthSettings.class);
        when(settings.restaurantId()).thenReturn(RESTAURANT);
        return new NotificationServiceTokens(new NotificationTrustSettings(env), settings, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static String token(Consumer<JwtBuilder> mutation) {
        JwtBuilder builder = builder();
        mutation.accept(builder);
        return builder.signWith(KEYS.getPrivate(), Jwts.SIG.RS256).compact();
    }

    private static JwtBuilder builder() {
        return Jwts.builder().issuer("notification-service").subject("notification-service")
                .audience().add("restofy-auth").and().claim("token_type", "service").claim("scope", "staff:recipients:read")
                .claim("restaurant_id", RESTAURANT.toString()).id(UUID.randomUUID().toString())
                .issuedAt(Date.from(NOW)).notBefore(Date.from(NOW)).expiration(Date.from(NOW.plusSeconds(60)));
    }
}

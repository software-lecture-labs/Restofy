package com.restofy.waiter.infrastructure.config;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The configuration of the browser authentication fails closed: a half-configured or weak setup does not start. */
class AuthPropertiesTests {

    private static final String KEY = Base64.getEncoder()
            .encodeToString(Jwts.SIG.RS256.keyPair().build().getPublic().getEncoded());
    private static final String BASE = "https://auth.example.test";

    @Test
    void withoutAKeyStaffAuthenticationIsOffAndNothingElseIsRequired() {
        AuthProperties off = new AuthProperties("", "", "", 0, List.of());
        assertFalse(off.staffAuthenticationEnabled());
        assertTrue(off.originSet().isEmpty());
    }

    @Test
    void aValidSetupDerivesTheSessionEndpointFromTheOrigin() {
        AuthProperties on = new AuthProperties(BASE, KEY, "restofy-auth", 300, List.of("https://app.example.test"));
        assertTrue(on.staffAuthenticationEnabled());
        assertEquals("https://auth.example.test/api/v1/auth/session", on.sessionUri().toString());
        assertEquals(1, on.originSet().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "http://auth.example.test", "https://user@auth.example.test",
            "https://auth.example.test/path", "https://auth.example.test/?query=true", "not a url"})
    void staffAuthenticationRequiresAnHttpsOrigin(String url) {
        assertThrows(IllegalStateException.class, () -> new AuthProperties(url, KEY, "restofy-auth", 300, List.of()));
    }

    @Test
    void aMalformedOrWeakKeyIsRefused() {
        assertThrows(IllegalStateException.class, () -> new AuthProperties(BASE, "not-base64!", "restofy-auth", 300, List.of()));
        String weak = Base64.getEncoder().encodeToString(Jwts.SIG.ES256.keyPair().build().getPublic().getEncoded());
        assertThrows(IllegalStateException.class, () -> new AuthProperties(BASE, weak, "restofy-auth", 300, List.of()));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 301})
    void theLifetimeIsCappedAtTheLifetimeOfAuth(long ttl) {
        assertThrows(IllegalStateException.class, () -> new AuthProperties(BASE, KEY, "restofy-auth", ttl, List.of()));
    }

    @Test
    void anIssuerIsRequiredWhenStaffAuthenticationIsOn() {
        assertThrows(IllegalStateException.class, () -> new AuthProperties(BASE, KEY, " ", 300, List.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "app.example.test", "https://app.example.test/path", "https://u@app.example.test",
            "ftp://app.example.test", "https://app.example.test?x=1"})
    void onlyExplicitHttpOriginsAreAllowed(String origin) {
        List<String> origins = List.of(origin);
        assertThrows(IllegalStateException.class, () -> new AuthProperties("", "", "", 0, origins));
    }

    @Test
    void originsAreTrimmedAndDeduplicated() {
        AuthProperties properties = new AuthProperties("", "", "", 0,
                List.of(" https://app.example.test ", "https://app.example.test", ""));
        assertEquals(List.of("https://app.example.test"), properties.allowedOrigins());
    }
}

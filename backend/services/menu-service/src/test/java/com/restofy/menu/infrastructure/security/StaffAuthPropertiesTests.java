package com.restofy.menu.infrastructure.security;

import com.restofy.menu.support.TestStaffTokens;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The startup rules of the staff-authentication settings: fail closed when blank, fail fast when wrong, never print a value. */
class StaffAuthPropertiesTests {

    private static final String KEY = TestStaffTokens.authPublicKey();

    @Test
    void blankSettingsMeanStaffAuthenticationIsOffAndNoMutationOriginIsAllowed() {
        StaffAuthProperties blank = new StaffAuthProperties(null, null, null);
        assertFalse(blank.staffAuthenticationEnabled());
        assertTrue(blank.originSet().isEmpty());
        assertFalse(new StaffAuthProperties("  ", "  ", List.of(" ", "")).staffAuthenticationEnabled());
    }

    @Test
    void aValidKeyAndIssuerTurnItOn() {
        StaffAuthProperties on = new StaffAuthProperties(" " + KEY + " ", " restofy-auth ", List.of());
        assertTrue(on.staffAuthenticationEnabled());
        assertEquals("restofy-auth", on.issuer());
        assertEquals(2048, on.rsaPublicKey().getModulus().bitLength());
    }

    @Test
    void aKeyWithoutAnIssuerIsRefusedAtStartup() {
        var failure = assertThrows(IllegalStateException.class, () -> new StaffAuthProperties(KEY, "", List.of()));
        assertTrue(failure.getMessage().contains("JWT_ISSUER"));
    }

    @Test
    void aMalformedOrTooWeakKeyIsRefusedAndNeverPrinted() throws Exception {
        for (String bad : new String[] {"not-base64!!", "AAAA", Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}),
                KEY.substring(0, KEY.length() / 2), weakKey()}) {
            var failure = assertThrows(IllegalStateException.class, () -> new StaffAuthProperties(bad, "restofy-auth", List.of()));
            assertTrue(failure.getMessage().contains("MENU_AUTH_STAFF_PUBLIC_KEY"), failure.getMessage());
            assertFalse(failure.getMessage().contains(bad), "the value must never appear in the error");
            assertEquals(null, failure.getCause());
        }
    }

    @Test
    void aPrivateKeyIsNotAcceptedInPlaceOfThePublicOne() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String pkcs8 = Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        assertThrows(IllegalStateException.class, () -> new StaffAuthProperties(pkcs8, "restofy-auth", List.of()));
    }

    @Test
    void onlyExplicitOriginsAreAccepted() {
        StaffAuthProperties ok = new StaffAuthProperties("", "", List.of(" http://localhost:5000 ", "https://app.example.com",
                "http://localhost:5000", ""));
        assertEquals(java.util.Set.of("http://localhost:5000", "https://app.example.com"), ok.originSet());
        for (String bad : new String[] {"*", "https://*.example.com", "https://app.example.com/", "https://app.example.com/path",
                "https://user@app.example.com", "https://app.example.com?x=1", "https://app.example.com#f", "ftp://app.example.com",
                "app.example.com", "https://app.example.com:99999", "null", "http://"}) {
            var failure = assertThrows(IllegalStateException.class, () -> new StaffAuthProperties("", "", List.of(bad)), bad);
            assertTrue(failure.getMessage().contains("CORS_ALLOWED_ORIGINS"));
            assertFalse(failure.getMessage().contains(bad) && bad.length() > 6, "the value must not be echoed: " + bad);
        }
    }

    private static String weakKey() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        return Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded());
    }
}

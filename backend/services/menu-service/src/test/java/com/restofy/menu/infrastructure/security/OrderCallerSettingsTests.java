package com.restofy.menu.infrastructure.security;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** What Menu trusts about Order is validated at startup, fails closed and never echoes a value. */
class OrderCallerSettingsTests {

    private static final String PUBLIC = Base64.getEncoder().encodeToString(Jwts.SIG.RS256.keyPair().build().getPublic().getEncoded());
    private static final UUID ALPHA = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID BETA = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final String KEYS = "{\"order-1\":\"" + PUBLIC + "\"}";

    @Test
    void nothingConfiguredTrustsNobodyAndAllowsNoRestaurant() {
        for (String empty : new String[] {null, "", "   "}) {
            OrderCallerSettings settings = OrderCallerSettings.parse(empty, empty);
            assertFalse(settings.trustsAnyone());
            assertNull(settings.key("order-1"));
            assertFalse(settings.mayRead("order-1", ALPHA));
        }
    }

    @Test
    void aKeyIsTrustedButReadsOnlyTheRestaurantsListedForIt() {
        OrderCallerSettings settings = OrderCallerSettings.parse(KEYS, "{\"order-1\":[\"" + ALPHA + "\"]}");

        assertTrue(settings.trustsAnyone());
        assertNotNull(settings.key("order-1"));
        assertTrue(settings.mayRead("order-1", ALPHA));
        assertFalse(settings.mayRead("order-1", BETA));
        assertFalse(settings.mayRead("unknown", ALPHA));
        assertFalse(settings.mayRead(null, ALPHA));
        assertNull(settings.key(null));
    }

    @Test
    void aKeyWithoutARestaurantEntryReadsNothingThereIsNoWildcard() {
        OrderCallerSettings settings = OrderCallerSettings.parse(KEYS, "");

        assertNotNull(settings.key("order-1"));
        assertFalse(settings.mayRead("order-1", ALPHA));
        OrderCallerSettings empty = OrderCallerSettings.parse(KEYS, "{\"order-1\":[]}");
        assertFalse(empty.mayRead("order-1", ALPHA));
        assertThrows(IllegalStateException.class, () -> OrderCallerSettings.parse(KEYS, "{\"order-1\":[\"*\"]}"));
    }

    @Test
    void invalidKeyMapsStopTheApplicationNamingTheSettingNotTheValue() {
        for (String bad : new String[] {"not json", "[]", "\"x\"", "{\"order 1\":\"" + PUBLIC + "\"}", "{\"order-1\":42}",
                "{\"order-1\":\"AAAA\"}", "{\"order-1\":\"not-base64!!\"}", "{\"" + "k".repeat(65) + "\":\"" + PUBLIC + "\"}"}) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> OrderCallerSettings.parse(bad, ""), bad);
            assertTrue(e.getMessage().contains("MENU_ORDER_CALLER_PUBLIC_KEYS"));
            assertFalse(e.getMessage().contains(PUBLIC));
            assertFalse(e.getMessage().contains("not-base64"));
        }
    }

    @Test
    void aWeakRsaKeyIsRefused() throws Exception {
        var generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        String weak = Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded());

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> OrderCallerSettings.parse("{\"order-1\":\"" + weak + "\"}", ""));
        assertFalse(e.getMessage().contains(weak));
    }

    @Test
    void invalidRestaurantMapsStopTheApplication() {
        for (String bad : new String[] {"not json", "[]", "{\"order-1\":\"" + ALPHA + "\"}", "{\"other\":[\"" + ALPHA + "\"]}",
                "{\"order-1\":[\"not-a-uuid\"]}", "{\"order-1\":[\"" + ALPHA.toString().toUpperCase() + "\"]}",
                "{\"order-1\":[\"" + ALPHA.toString().replace("-", "") + "\"]}", "{\"order-1\":[1]}"}) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> OrderCallerSettings.parse(KEYS, bad), bad);
            assertTrue(e.getMessage().contains("MENU_ORDER_CALLER_RESTAURANTS"));
            assertFalse(e.getMessage().contains(ALPHA.toString()));
        }
    }

    @Test
    void restaurantsForAKeyThatIsNotConfiguredAreRefused() {
        assertThrows(IllegalStateException.class, () -> OrderCallerSettings.parse("", "{\"order-1\":[\"" + ALPHA + "\"]}"));
    }

    @Test
    void theToStringShowsNoKeyMaterial() {
        assertFalse(OrderCallerSettings.parse(KEYS, "{\"order-1\":[\"" + ALPHA + "\"]}").toString().contains(PUBLIC));
    }
}

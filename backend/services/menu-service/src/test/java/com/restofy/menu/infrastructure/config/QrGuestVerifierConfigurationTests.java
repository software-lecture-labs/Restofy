package com.restofy.menu.infrastructure.config;

import com.restofy.menu.application.security.GuestSessionVerifier;
import com.restofy.menu.infrastructure.client.QrGuestSessionVerifier;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The QR connection is off unless Menu's own key is configured, and a configuration that is wrong stops the application at
 * startup with a message that names the setting and never shows a value.
 */
class QrGuestVerifierConfigurationTests {

    private static final String KEY = Base64.getEncoder().encodeToString(Jwts.SIG.RS256.keyPair().build().getPrivate().getEncoded());

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(QrGuestVerifierConfiguration.class);

    private ApplicationContextRunner configured(String url, String keyId, String key) {
        return runner.withPropertyValues("restofy.menu.qr.service-url=" + url, "restofy.menu.qr.caller-key-id=" + keyId,
                "restofy.menu.qr.caller-private-key=" + key);
    }

    private static String failure(org.springframework.boot.test.context.assertj.AssertableApplicationContext context) {
        assertNotNull(context.getStartupFailure());
        Throwable root = context.getStartupFailure();
        StringBuilder all = new StringBuilder();
        for (Throwable t = root; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append('\n');
        }
        return all.toString();
    }

    @Test
    void withoutAPrivateKeyTheConnectionIsOffAndNothingIsRegistered() {
        runner.run(context -> assertTrue(context.getBeansOfType(GuestSessionVerifier.class).isEmpty()));
        runner.withPropertyValues("restofy.menu.qr.service-url=https://qr.example.org", "restofy.menu.qr.caller-key-id=k")
                .run(context -> assertTrue(context.getBeansOfType(GuestSessionVerifier.class).isEmpty(),
                        "a URL alone connects nothing"));
    }

    @Test
    void aCompleteConfigurationRegistersTheRealVerifier() {
        configured("https://qr.example.org", "menu-key-1", KEY).run(context -> {
            assertNull(context.getStartupFailure());
            assertInstanceOf(QrGuestSessionVerifier.class, context.getBean(GuestSessionVerifier.class));
        });
    }

    @Test
    void plainHttpIsAcceptedOnlyForALoopbackHost() {
        configured("http://localhost:8080", "k", KEY).run(context -> assertNull(context.getStartupFailure()));
        configured("http://127.0.0.1:8080", "k", KEY).run(context -> assertNull(context.getStartupFailure()));
        configured("http://qr-service:8080", "k", KEY).run(context -> assertTrue(failure(context).contains("QR_SERVICE_URL")));
    }

    @Test
    void anUnsafeOrMalformedAddressStopsTheApplicationWithoutEchoingIt() {
        for (String url : new String[] {"", "qr.example.org", "ftp://qr.example.org", "https://user:secret@qr.example.org",
                "https://qr.example.org/path", "https://qr.example.org?x=1", "https://qr.example.org#f", "https://",
                "https://qr.example.org:0", "https://qr.example.org:99999", "http://evil.example.org", "http://localhost.evil.org"}) {
            configured(url, "k", KEY).run(context -> {
                String message = failure(context);
                assertTrue(message.contains("QR_SERVICE_URL"), url + " -> " + message);
                for (String leaked : new String[] {"secret", "evil", "qr.example.org"}) {
                    assertFalse(message.contains(leaked), url + " leaked " + leaked + ": " + message);
                }
            });
        }
    }

    @Test
    void aBadKeyIdStopsTheApplication() {
        for (String keyId : new String[] {"", "a b", "k".repeat(65), "k/../x", "k\nx"}) {
            configured("https://qr.example.org", keyId, KEY).run(context -> assertTrue(failure(context).contains("MENU_QR_CALLER_KEY_ID")));
        }
    }

    @Test
    void aBadOrWeakPrivateKeyStopsTheApplicationWithoutEchoingIt() throws Exception {
        KeyPairGenerator weak = KeyPairGenerator.getInstance("RSA");
        weak.initialize(1024);
        KeyPair small = weak.generateKeyPair();
        String weakKey = Base64.getEncoder().encodeToString(small.getPrivate().getEncoded());
        String publicOnly = Base64.getEncoder().encodeToString(Jwts.SIG.RS256.keyPair().build().getPublic().getEncoded());
        for (String key : new String[] {"not-base64!!", "AAAA", weakKey, publicOnly}) {
            configured("https://qr.example.org", "k", key).run(context -> {
                String message = failure(context);
                assertTrue(message.contains("MENU_QR_CALLER_PRIVATE_KEY"), message);
                assertFalse(message.contains(key), "the key value must not be echoed");
            });
        }
    }

    @Test
    void theTimeoutsMustBePositive() {
        configured("https://qr.example.org", "k", KEY).withPropertyValues("restofy.menu.qr.connect-timeout-ms=0")
                .run(context -> assertTrue(failure(context).contains("timeouts")));
    }
}

package com.restofy.menu.infrastructure.config;

import com.restofy.menu.application.security.GuestSessionVerifier;
import com.restofy.menu.infrastructure.client.QrGuestSessionVerifiers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Clock;

/**
 * Connects Menu to QR's guest-session check, but only when Menu's own signing key is configured
 * ({@code MENU_QR_CALLER_PRIVATE_KEY}). Without it nothing changes: the fail-closed default stays and every public menu
 * request is a 404 (ADR-MENU-008). With it, anything wrong in the configuration (the QR address, the key id, the key itself) stops
 * the application at startup with a message that names the setting and never shows a value: a half configured connection
 * is not a silent fallback.
 *
 * <p>The settings: {@code QR_SERVICE_URL} (an existing variable: an HTTPS origin; plain HTTP only for a loopback host),
 * {@code MENU_QR_CALLER_KEY_ID} and {@code MENU_QR_CALLER_PRIVATE_KEY} (Base64 PKCS#8 RSA, at least 2048 bits). QR is given
 * the matching PUBLIC key as {@code QR_MENU_CALLER_PUBLIC_KEYS}. The private key never leaves Menu.
 */
@Configuration(proxyBeanMethods = false)
class QrGuestVerifierConfiguration {

    @Bean(destroyMethod = "close")
    @Primary
    @ConditionalOnExpression("'${restofy.menu.qr.caller-private-key:}' != ''")
    GuestSessionVerifier qrGuestSessionVerifier(
            @Value("${restofy.menu.qr.service-url:}") String serviceUrl,
            @Value("${restofy.menu.qr.caller-key-id:}") String keyId,
            @Value("${restofy.menu.qr.caller-private-key:}") String privateKey,
            @Value("${restofy.menu.qr.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${restofy.menu.qr.read-timeout-ms:3000}") int readTimeoutMs) {
        return QrGuestSessionVerifiers.create(Clock.systemUTC(), serviceUrl, keyId, privateKey, connectTimeoutMs, readTimeoutMs);
    }
}

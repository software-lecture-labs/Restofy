package com.restofy.menu.infrastructure.client;

import com.restofy.menu.application.security.GuestSessionVerifier;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;

/**
 * Builds the {@link QrGuestSessionVerifier} from its settings and refuses anything unsafe at startup. Every error names the
 * setting and never echoes a value: a bad address can carry credentials and a bad key is a secret.
 */
public final class QrGuestSessionVerifiers {

    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]");

    private QrGuestSessionVerifiers() {
    }

    public static GuestSessionVerifier create(Clock clock, String serviceUrl, String keyId, String privateKey,
            int connectTimeoutMs, int readTimeoutMs) {
        URI origin = origin(serviceUrl);
        if (keyId == null || !keyId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalStateException("MENU_QR_CALLER_KEY_ID must be 1 to 64 characters of letters, digits, '.', '_' or '-'");
        }
        if (connectTimeoutMs < 1 || readTimeoutMs < 1) {
            throw new IllegalStateException("The QR timeouts must be greater than zero");
        }
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        RestClient http = RestClient.builder().requestFactory(factory).build();
        return new QrGuestSessionVerifier(origin, new QrRequestSigner(clock, keyId, key(privateKey)), httpClient, http);
    }

    /**
     * The guest token travels in the body, so the channel must be protected: HTTPS, or plain HTTP only to a loopback host
     * (the same machine, nothing on the network). A bare origin: no user info, path, query or fragment.
     */
    private static URI origin(String value) {
        try {
            URI candidate = URI.create(value == null ? "" : value);
            String scheme = candidate.getScheme();
            boolean secure = "https".equalsIgnoreCase(scheme);
            boolean loopback = "http".equalsIgnoreCase(scheme) && candidate.getHost() != null
                    && LOOPBACK.contains(candidate.getHost().toLowerCase(java.util.Locale.ROOT));
            if ((secure || loopback) && candidate.getHost() != null
                    && (candidate.getPort() == -1 || candidate.getPort() > 0 && candidate.getPort() <= 65535)
                    && candidate.getRawUserInfo() == null && candidate.getRawQuery() == null
                    && candidate.getRawFragment() == null
                    && (candidate.getRawPath().isEmpty() || "/".equals(candidate.getRawPath()))) {
                return candidate;
            }
        } catch (IllegalArgumentException _) {
            // Do not include an invalid URL that could carry credentials in a startup error.
        }
        throw new IllegalStateException("QR_SERVICE_URL must be an HTTPS origin (plain HTTP only for localhost) for the Menu QR connection");
    }

    private static RSAPrivateKey key(String value) {
        try {
            var key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(value)));
            if (key instanceof RSAPrivateKey rsa && rsa.getModulus().bitLength() >= 2048) {
                return rsa;
            }
        } catch (IllegalArgumentException | GeneralSecurityException _) {
            // Startup errors must never contain the input or the parser's diagnostic.
        }
        throw new IllegalStateException("MENU_QR_CALLER_PRIVATE_KEY must be a Base64 PKCS#8 RSA key of at least 2048 bits");
    }
}

package com.restofy.auth.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.restofy.auth.application.service.StaffSessionService;
import com.restofy.auth.infrastructure.config.AuthSettings;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.stereotype.Component;

@Component
public class AuthCsrfTokens implements CsrfTokenRepository {
    private static final Duration TOKEN_TTL = Duration.ofMinutes(15);
    private static final int NONCE_BYTES = 32;
    /** A token is "expiry.nonce.signature"; the cookie value stays far below this length. */
    private static final int MAX_COOKIE_LENGTH = 180;
    private static final int TOKEN_PARTS = 3;
    /** 32 bytes encoded as unpadded base64url. */
    private static final String BASE64URL_256_BITS = "[A-Za-z0-9_-]{43}";
    private final AuthSettings settings;
    private final AuthCookies cookies;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AuthCsrfTokens(AuthSettings settings, AuthCookies cookies, Clock clock) {
        this.settings = settings;
        this.cookies = cookies;
        this.clock = clock;
    }

    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        return create(binding(request));
    }

    public CsrfToken create(String binding) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        String payload = clock.instant().plus(TOKEN_TTL).getEpochSecond() + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        return token(payload + "." + sign(payload, binding));
    }

    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        cookies.write(response, AuthCookies.CSRF, token == null ? "" : token.getToken(), "/api/v1", false,
                token == null ? 0 : TOKEN_TTL.toSeconds());
    }

    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        String value = AuthCookies.read(request, AuthCookies.CSRF);
        if (value == null || value.length() > MAX_COOKIE_LENGTH) {
            return null;
        }
        String[] parts = value.split("\\.", -1);
        try {
            if (parts.length != TOKEN_PARTS || !parts[1].matches(BASE64URL_256_BITS)
                    || !parts[2].matches(BASE64URL_256_BITS)
                    || clock.instant().getEpochSecond() >= Long.parseLong(parts[0])
                    || Long.parseLong(parts[0]) > clock.instant().plus(TOKEN_TTL).getEpochSecond()) {
                return null;
            }
            String expected = sign(parts[0] + "." + parts[1], binding(request));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    parts[2].getBytes(StandardCharsets.US_ASCII)) ? token(value) : null;
        } catch (NumberFormatException _) {
            return null;
        }
    }

    public static String binding(HttpServletRequest request) {
        String refresh = AuthCookies.read(request, AuthCookies.REFRESH);
        String pending = AuthCookies.read(request, AuthCookies.MFA);
        return binding(refresh, pending);
    }

    public static String binding(String refresh, String pending) {
        if (refresh != null) {
            return StaffSessionService.digest(refresh);
        }
        return pending == null ? "anonymous" : StaffSessionService.digest(pending);
    }

    private String sign(String payload, String binding) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(settings.csrfSecret(), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal((payload + ":" + binding).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("CSRF signing unavailable", exception);
        }
    }

    private static CsrfToken token(String value) {
        return new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", value);
    }
}

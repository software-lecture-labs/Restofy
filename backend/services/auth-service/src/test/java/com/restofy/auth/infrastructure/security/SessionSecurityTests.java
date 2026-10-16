package com.restofy.auth.infrastructure.security;

import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import com.restofy.auth.domain.entity.StaffUser;
import com.restofy.auth.domain.entity.UserSession;
import com.restofy.auth.domain.enums.StaffRole;
import com.restofy.auth.infrastructure.config.AuthSettings;
import com.restofy.auth.support.AuthSessionTestSupport;
import com.restofy.auth.support.MutableAuthClock;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

import static org.assertj.core.api.Assertions.*;

class SessionSecurityTests {
    private static final java.security.KeyPair KEYS = AuthSessionTestSupport.generateKeys();
    private static final String HASH = new StaffPasswords().hash("Session-test-password-01");
    private final MutableAuthClock clock = new MutableAuthClock();
    private AuthSettings settings;
    private StaffJwtTokens jwt;
    private UserSession session;

    static MockEnvironment environment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        environment.withProperty("auth.session.restaurant-id", "6c54daf0-9340-4a1c-981d-f9acc0261779")
                .withProperty("auth.session.private-key", Base64.getEncoder().encodeToString(KEYS.getPrivate().getEncoded()))
                .withProperty("auth.session.csrf-secret", Base64.getEncoder().encodeToString("Test-only-secret-32-bytes-0123456789".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .withProperty("auth.session.cookie-secure", "false").withProperty("auth.session.issuer", "restofy-auth")
                .withProperty("auth.session.access-ttl-seconds", "900").withProperty("auth.session.refresh-ttl-seconds", "604800")
                .withProperty("auth.session.allowed-origins", "http://localhost:5000");
        return environment;
    }

    @BeforeEach
    void setup() {
        settings = new AuthSettings(environment());
        jwt = new StaffJwtTokens(settings, clock);
        session = new UserSession(new StaffUser("staff@example.com", HASH, StaffRole.WAITER), clock.instant(), 604800, null);
    }

    @Test
    void signsAndVerifiesRequiredClaimsWithoutKeyIdentifier() {
        String raw = jwt.issue(session);
        var identity = jwt.verify(raw, false);
        assertThat(identity.sessionId()).isEqualTo(session.getId());
        assertThat(identity.userId()).isEqualTo(session.getStaffUserId());
        assertThat(identity.role()).isEqualTo(StaffRole.WAITER);
        assertThat(Jwts.parser().verifyWith(settings.publicKey()).clock(() -> Date.from(clock.instant()))
                .build().parseSignedClaims(raw).getHeader()).doesNotContainKey("kid");
    }

    @Test
    void rejectsInvalidSignatureWrongAlgorithmAndUnsignedTokens() {
        var foreign = AuthSessionTestSupport.generateKeys();
        String foreignToken = builder().signWith(foreign.getPrivate(), Jwts.SIG.RS256).compact();
        String wrongAlgorithm = builder().signWith(settings.privateKey(), Jwts.SIG.RS512).compact();
        String unsigned = builder().compact();
        for (String raw : new String[]{null, "malformed", "x".repeat(4097), foreignToken, wrongAlgorithm, unsigned}) {
            assertThatThrownBy(() -> jwt.verify(raw, false)).isInstanceOf(BadCredentialsException.class);
        }
    }

    @Test
    void rejectsWrongIssuerAudienceTypeRestaurantRoleAndMissingClaims() {
        Map<String, Object> overrides = Map.of("iss", "wrong", "aud", "wrong", "token_type", "refresh",
                "restaurant_id", UUID.randomUUID().toString(), "auth_state", "MFA_PENDING", "role", "ADMIN",
                "sid", "invalid", "sub", "invalid", "jti", "invalid");
        overrides.forEach((name, value) -> {
            String raw = builder().claim(name, value).signWith(settings.privateKey(), Jwts.SIG.RS256).compact();
            assertThatThrownBy(() -> jwt.verify(raw, false)).as(name).isInstanceOf(BadCredentialsException.class);
        });
        for (String name : new String[]{"exp", "iat", "nbf", "jti", "sid", "sub", "role"}) {
            String raw = builder().claim(name, null).signWith(settings.privateKey(), Jwts.SIG.RS256).compact();
            assertThatThrownBy(() -> jwt.verify(raw, false)).as(name).isInstanceOf(BadCredentialsException.class);
        }
    }

    @Test
    void rejectsExpiredFutureAndOverlongTokens() {
        String valid = jwt.issue(session);
        clock.advance(900);
        assertThatThrownBy(() -> jwt.verify(valid, false)).isInstanceOf(BadCredentialsException.class);
        clock.reset();
        for (var claims : java.util.List.of(
                builder().notBefore(Date.from(clock.instant().plusSeconds(1))),
                builder().issuedAt(Date.from(clock.instant().plusSeconds(1))),
                builder().expiration(Date.from(clock.instant().plusSeconds(901))),
                builder().issuedAt(Date.from(clock.instant().plusSeconds(900))))) {
            String raw = claims.signWith(settings.privateKey(), Jwts.SIG.RS256).compact();
            assertThatThrownBy(() -> jwt.verify(raw, false)).isInstanceOf(BadCredentialsException.class);
        }
    }

    @ParameterizedTest
    @MethodSource("unsafeSettings")
    void rejectsUnsafeConfigurationWithoutLeakingValues(String name, String value) {
        MockEnvironment environment = environment().withProperty("auth.session." + name, value);
        assertThatThrownBy(() -> new AuthSettings(environment)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid or missing Auth session configuration").hasNoCause();
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> unsafeSettings() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("private-key", "invalid-secret"),
                org.junit.jupiter.params.provider.Arguments.of("restaurant-id", "invalid"),
                org.junit.jupiter.params.provider.Arguments.of("restaurant-id", new UUID(0, 0).toString()),
                org.junit.jupiter.params.provider.Arguments.of("issuer", ""),
                org.junit.jupiter.params.provider.Arguments.of("issuer", "x".repeat(101)),
                org.junit.jupiter.params.provider.Arguments.of("csrf-secret", "invalid"),
                org.junit.jupiter.params.provider.Arguments.of("csrf-secret", Base64.getEncoder().encodeToString(new byte[32])),
                org.junit.jupiter.params.provider.Arguments.of("csrf-secret", Base64.getEncoder().encodeToString(new byte[16])),
                org.junit.jupiter.params.provider.Arguments.of("cookie-secure", "maybe"),
                org.junit.jupiter.params.provider.Arguments.of("access-ttl-seconds", "901"),
                org.junit.jupiter.params.provider.Arguments.of("refresh-ttl-seconds", "0"),
                org.junit.jupiter.params.provider.Arguments.of("allowed-origins", "*"),
                org.junit.jupiter.params.provider.Arguments.of("allowed-origins", "http://example.com"),
                org.junit.jupiter.params.provider.Arguments.of("allowed-origins", "https://example.com/path"),
                org.junit.jupiter.params.provider.Arguments.of("allowed-origins", "https://user@example.com"),
                org.junit.jupiter.params.provider.Arguments.of("allowed-origins", "https://example.com?query=1"),
                org.junit.jupiter.params.provider.Arguments.of("allowed-origins", "https://example.com#fragment"));
    }

    @Test
    void productionRequiresSecureCookiesAndHttpsAndMissingSettingsFailClosed() {
        MockEnvironment environment = environment();
        environment.setActiveProfiles("prod");
        assertThatThrownBy(() -> new AuthSettings(environment)).isInstanceOf(IllegalStateException.class);
        environment.withProperty("auth.session.cookie-secure", "true");
        assertThatThrownBy(() -> new AuthSettings(environment)).isInstanceOf(IllegalStateException.class);
        environment.withProperty("auth.session.allowed-origins", "https://restofy.example");
        AuthSettings production = new AuthSettings(environment);
        assertThat(production.secure()).isTrue();
        var cookies = new AuthCookies(production, clock);
        var response = new MockHttpServletResponse();
        cookies.write(response, AuthCookies.ACCESS, "test-only", "/api/v1", true, 900);
        assertThat(response.getHeader("Set-Cookie")).contains("Secure", "HttpOnly", "SameSite=Lax", "Path=/api/v1").doesNotContain("Domain=");
        MockEnvironment empty = new MockEnvironment();
        assertThatThrownBy(() -> new AuthSettings(empty)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void csrfIsSignedBoundToCredentialAndExpires() {
        var cookies = new AuthCookies(settings, clock);
        var csrf = new AuthCsrfTokens(settings, cookies, clock);
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        var token = csrf.generateToken(request);
        csrf.saveToken(token, request, response);
        request.setCookies(new Cookie(AuthCookies.CSRF, token.getToken()));
        assertThat(csrf.loadToken(request).getToken()).isEqualTo(token.getToken());
        request.setCookies(new Cookie(AuthCookies.CSRF, token.getToken()), new Cookie(AuthCookies.REFRESH, "different"));
        assertThat(csrf.loadToken(request)).isNull();
        clock.advance(900);
        request.setCookies(new Cookie(AuthCookies.CSRF, token.getToken()));
        assertThat(csrf.loadToken(request)).isNull();
        csrf.saveToken(null, request, response);
        assertThat(response.getHeaders("Set-Cookie")).anyMatch(value -> value.contains("Max-Age=0"));
        assertThat(AuthCsrfTokens.binding(null, "pending")).isEqualTo(sessionDigest("pending"));
    }

    @Test
    void csrfRejectsMalformedForgedFutureAndDuplicateCookies() {
        var csrf = new AuthCsrfTokens(settings, new AuthCookies(settings, clock), clock);
        var request = new MockHttpServletRequest();
        assertThat(csrf.loadToken(request)).isNull();
        String valid = csrf.generateToken(request).getToken();
        for (String raw : new String[]{"x".repeat(181), "invalid", "bad." + "a".repeat(43) + "." + "b".repeat(43),
                clock.instant().plusSeconds(901).getEpochSecond() + "." + "a".repeat(43) + "." + "b".repeat(43),
                valid.substring(0, valid.length() - 43) + "c".repeat(43)}) {
            request.setCookies(new Cookie(AuthCookies.CSRF, raw));
            assertThat(csrf.loadToken(request)).isNull();
        }
        request.setCookies(new Cookie(AuthCookies.ACCESS, "first"), new Cookie(AuthCookies.ACCESS, "second"));
        assertThatThrownBy(() -> AuthCookies.read(request, AuthCookies.ACCESS)).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void loginLimiterNormalizesAccountsExpiresWindowsAndBoundsMemory() {
        LoginRateLimiter limiter = new LoginRateLimiter(clock);
        for (int attempt = 0; attempt < 10; attempt++) { limiter.check(" STAFF@EXAMPLE.COM "); }
        assertThatThrownBy(() -> limiter.check("staff@example.com"))
                .isInstanceOfSatisfying(LoginRateLimiter.LoginLimitException.class, exception -> assertThat(exception.retryAfter()).isEqualTo(900));
        limiter.check("other@example.com");
        limiter.check(null);
        clock.advance(900);
        assertThatCode(() -> limiter.check("staff@example.com")).doesNotThrowAnyException();
        LoginRateLimiter bounded = new LoginRateLimiter(clock);
        for (int account = 0; account < 10000; account++) { bounded.check("staff" + account + "@example.com"); }
        assertThatThrownBy(() -> bounded.check("overflow@example.com")).isInstanceOf(LoginRateLimiter.LoginLimitException.class);
    }

    private io.jsonwebtoken.JwtBuilder builder() {
        Instant now = clock.instant();
        return Jwts.builder().subject(session.getStaffUserId().toString()).issuer(settings.issuer())
                .audience().add("restofy-staff-api").and().issuedAt(Date.from(now)).notBefore(Date.from(now))
                .expiration(Date.from(now.plusSeconds(900))).id(UUID.randomUUID().toString())
                .claim("sid", session.getId().toString()).claim("role", "WAITER").claim("auth_state", "AUTHENTICATED")
                .claim("restaurant_id", settings.restaurantId().toString()).claim("token_type", "access");
    }

    private static String sessionDigest(String value) {
        return com.restofy.auth.application.service.StaffSessionService.digest(value);
    }
}

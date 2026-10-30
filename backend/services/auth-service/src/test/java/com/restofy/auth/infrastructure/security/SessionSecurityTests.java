package com.restofy.auth.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import com.restofy.auth.application.service.StaffSessionService;
import com.restofy.auth.domain.entity.StaffUser;
import com.restofy.auth.domain.entity.UserSession;
import com.restofy.auth.domain.enums.SessionAuthState;
import com.restofy.auth.domain.enums.StaffRole;
import com.restofy.auth.infrastructure.config.AuthSettings;
import com.restofy.auth.support.AuthSessionTestSupport;
import com.restofy.auth.support.MutableAuthClock;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class SessionSecurityTests {
    private static final int MIN_DISTINCT_ERROR_VALUE_LENGTH = 3;
    private static final KeyPair KEYS = AuthSessionTestSupport.generateKeys();
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
                .withProperty("auth.session.csrf-secret", Base64.getEncoder().encodeToString("Test-only-secret-32-bytes-0123456789"
                        .getBytes(StandardCharsets.UTF_8)))
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
                "restaurant_id", UUID.randomUUID().toString(), "auth_state", "MFA_PENDING", "role", "UNKNOWN",
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
    void acceptsAdminAccessOnlyAfterPromotionAndRejectsPendingForNonAdmin() {
        UserSession admin = new UserSession(new StaffUser("admin@example.com", HASH, StaffRole.ADMIN), clock.instant(), 604800, null);
        String pending = jwt.issue(admin);
        assertThat(jwt.verify(pending, true).state()).isEqualTo(SessionAuthState.MFA_PENDING);
        assertThatThrownBy(() -> jwt.verify(pending, false)).isInstanceOf(BadCredentialsException.class);
        admin.completeMfa(clock.instant(), 604800);
        String access = jwt.issue(admin);
        assertThat(jwt.verify(access, false).role()).isEqualTo(StaffRole.ADMIN);
        assertThatThrownBy(() -> jwt.verify(access, true)).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void rejectsExpiredFutureAndOverlongTokens() {
        String valid = jwt.issue(session);
        clock.advance(900);
        assertThatThrownBy(() -> jwt.verify(valid, false)).isInstanceOf(BadCredentialsException.class);
        clock.reset();
        for (var claims : List.of(
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
    void rejectsUnsafeConfigurationNamingTheVariableButNeverItsValue(String name, String variable, String value) {
        MockEnvironment environment = environment().withProperty("auth.session." + name, value);
        var failure = assertThatThrownBy(() -> new AuthSettings(environment)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid or missing " + variable + ": expected").hasNoCause();
        if (value.length() > MIN_DISTINCT_ERROR_VALUE_LENGTH) {
            failure.message().doesNotContain(value);
        }
    }

    static Stream<Arguments> unsafeSettings() {
        return Stream.of(
                arguments("private-key", "AUTH_JWT_PRIVATE_KEY", "invalid-secret"),
                arguments("restaurant-id", "AUTH_RESTAURANT_ID", "invalid"),
                arguments("restaurant-id", "AUTH_RESTAURANT_ID", new UUID(0, 0).toString()),
                arguments("issuer", "JWT_ISSUER", ""),
                arguments("issuer", "JWT_ISSUER", "x".repeat(101)),
                arguments("csrf-secret", "AUTH_CSRF_SECRET", "invalid"),
                arguments("csrf-secret", "AUTH_CSRF_SECRET", Base64.getEncoder().encodeToString(new byte[32])),
                arguments("csrf-secret", "AUTH_CSRF_SECRET", Base64.getEncoder().encodeToString(new byte[16])),
                arguments("cookie-secure", "AUTH_COOKIE_SECURE", "maybe"),
                arguments("access-ttl-seconds", "JWT_ACCESS_TTL_SECONDS", "299"),
                arguments("access-ttl-seconds", "JWT_ACCESS_TTL_SECONDS", "3601"),
                arguments("access-ttl-seconds", "JWT_ACCESS_TTL_SECONDS", "fifteen"),
                arguments("refresh-ttl-seconds", "JWT_REFRESH_TTL_SECONDS", "0"),
                arguments("refresh-ttl-seconds", "JWT_REFRESH_TTL_SECONDS", "86399"),
                arguments("refresh-ttl-seconds", "JWT_REFRESH_TTL_SECONDS", "2592001"),
                arguments("allowed-origins", "CORS_ALLOWED_ORIGINS", "*"),
                arguments("allowed-origins", "CORS_ALLOWED_ORIGINS", "http://example.com"),
                arguments("allowed-origins", "CORS_ALLOWED_ORIGINS", "https://example.com/path"),
                arguments("allowed-origins", "CORS_ALLOWED_ORIGINS", "https://user@example.com"),
                arguments("allowed-origins", "CORS_ALLOWED_ORIGINS", "https://example.com?query=1"),
                arguments("allowed-origins", "CORS_ALLOWED_ORIGINS", "https://example.com#fragment"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"300", "900", "3600"})
    void acceptsAccessLifetimesInsideTheConfiguredRange(String seconds) {
        MockEnvironment environment = environment().withProperty("auth.session.access-ttl-seconds", seconds);
        assertThat(new AuthSettings(environment).accessTtl()).isEqualTo(Long.parseLong(seconds));
    }

    @ParameterizedTest
    @ValueSource(strings = {"86400", "604800", "2592000"})
    void acceptsRefreshLifetimesInsideTheConfiguredRange(String seconds) {
        MockEnvironment environment = environment().withProperty("auth.session.refresh-ttl-seconds", seconds);
        assertThat(new AuthSettings(environment).refreshTtl()).isEqualTo(Long.parseLong(seconds));
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
        assertThat(response.getHeader("Set-Cookie")).contains("Secure", "HttpOnly", "SameSite=Lax", "Path=/api/v1").doesNotContain(
                "Domain=");
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

    private JwtBuilder builder() {
        Instant now = clock.instant();
        return Jwts.builder().subject(session.getStaffUserId().toString()).issuer(settings.issuer())
                .audience().add("restofy-staff-api").and().issuedAt(Date.from(now)).notBefore(Date.from(now))
                .expiration(Date.from(now.plusSeconds(900))).id(UUID.randomUUID().toString())
                .claim("sid", session.getId().toString()).claim("role", "WAITER").claim("auth_state", "AUTHENTICATED")
                .claim("restaurant_id", settings.restaurantId().toString()).claim("token_type", "access");
    }

    private static String sessionDigest(String value) {
        return StaffSessionService.digest(value);
    }
}

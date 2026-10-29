package com.restofy.auth.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import com.restofy.auth.domain.entity.StaffUser;
import com.restofy.auth.domain.entity.UserSession;
import com.restofy.auth.domain.enums.StaffRole;
import com.restofy.auth.infrastructure.config.AuthSettings;
import com.restofy.auth.support.AuthSessionTestSupport;
import com.restofy.auth.support.MutableAuthClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Consumer-side proof: standard JCA verifies Auth's wire signature without calling Auth's verifier or its DB. */
class StaffConsumerContractTests {
    private static final KeyPair KEYS = AuthSessionTestSupport.generateKeys();
    private static final UUID RESTAURANT = UUID.randomUUID();
    private static final int ACCESS_SECONDS = 900;
    private static final int SESSION_SECONDS = 604800;
    private static final int MAX_TOKEN_LENGTH = 4096;
    private static final int JWT_PART_COUNT = 3;
    private static final String HASH = new StaffPasswords().hash("Contract-test-password-01");
    private final MutableAuthClock clock = new MutableAuthClock();
    private final StaffJwtTokens producer = new StaffJwtTokens(settings(), clock);

    @ParameterizedTest
    @EnumSource(StaffRole.class)
    void independentConsumerReadsEveryStaffRoleFromSignedClaims(StaffRole role) throws Exception {
        UserSession session = session(role);
        String wire = producer.issue(session);
        JsonNode claims = consume(wire, clock.instant(), KEYS);
        assertThat(claims.get("sub").asText()).isEqualTo(session.getStaffUserId().toString());
        assertThat(claims.get("sid").asText()).isEqualTo(session.getId().toString());
        assertThat(claims.get("role").asText()).isEqualTo(role.name());
        assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isEqualTo(ACCESS_SECONDS);
        // Order's old 512-character Staff header limit cannot carry this real access JWT.
        assertThat(wire.length()).isGreaterThan(512).isLessThanOrEqualTo(MAX_TOKEN_LENGTH);
    }

    @Test
    void pendingAdminCannotBecomeAStaffPrincipal() {
        UserSession pending = new UserSession(new StaffUser("admin@example.com", HASH, StaffRole.ADMIN),
                clock.instant(), SESSION_SECONDS, null);
        assertThatThrownBy(() -> consume(producer.issue(pending), clock.instant(), KEYS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsWrongSignatureTamperingAndUnboundedInput() {
        String wire = producer.issue(session(StaffRole.WAITER));
        assertThatThrownBy(() -> consume(wire, clock.instant(), AuthSessionTestSupport.generateKeys()))
                .isInstanceOf(IllegalArgumentException.class);
        String[] parts = wire.split("\\.");
        parts[1] = Base64.getUrlEncoder().withoutPadding().encodeToString("{}".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> consume(String.join(".", parts), clock.instant(), KEYS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consume("x".repeat(MAX_TOKEN_LENGTH + 1), clock.instant(), KEYS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void localValidationKeepsRevokedTokenUntilItsExactExpiry() throws Exception {
        UserSession session = session(StaffRole.WAITER);
        String wire = producer.issue(session);
        session.revoke(clock.instant());
        assertThat(session.isUsable(clock.instant())).isFalse();
        assertThat(consume(wire, clock.instant().plusSeconds(ACCESS_SECONDS - 1), KEYS)).isNotNull();
        assertThatThrownBy(() -> consume(wire, clock.instant().plusSeconds(ACCESS_SECONDS), KEYS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void accessExpiryNeverOutlivesTheAbsoluteSessionDeadline() throws Exception {
        int remainingSeconds = 30;
        UserSession session = new UserSession(new StaffUser("waiter@example.com", HASH, StaffRole.WAITER),
                clock.instant(), remainingSeconds, null);
        JsonNode claims = consume(producer.issue(session), clock.instant(), KEYS);
        assertThat(claims.get("exp").asLong()).isEqualTo(session.getAbsoluteExpiresAt().getEpochSecond());
    }

    private UserSession session(StaffRole role) {
        UserSession result = new UserSession(new StaffUser("staff@example.com", HASH, role),
                clock.instant(), SESSION_SECONDS, null);
        if (role == StaffRole.ADMIN) {
            result.completeMfa(clock.instant(), SESSION_SECONDS);
        }
        return result;
    }

    private static AuthSettings settings() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        byte[] csrfSecret = new byte[32];
        new SecureRandom().nextBytes(csrfSecret);
        environment.withProperty("auth.session.restaurant-id", RESTAURANT.toString())
                .withProperty("auth.session.private-key", Base64.getEncoder().encodeToString(KEYS.getPrivate().getEncoded()))
                .withProperty("auth.session.csrf-secret", Base64.getEncoder().encodeToString(csrfSecret))
                .withProperty("auth.session.cookie-secure", "false")
                .withProperty("auth.session.issuer", "restofy-auth")
                .withProperty("auth.session.access-ttl-seconds", Integer.toString(ACCESS_SECONDS))
                .withProperty("auth.session.refresh-ttl-seconds", Integer.toString(SESSION_SECONDS))
                .withProperty("auth.session.allowed-origins", "http://localhost:5000");
        return new AuthSettings(environment);
    }

    private static JsonNode consume(String wire, Instant now, KeyPair trustedKeys) throws Exception {
        require(wire != null && wire.length() <= MAX_TOKEN_LENGTH);
        String[] parts = wire.split("\\.", -1);
        require(parts.length == JWT_PART_COUNT);
        Base64.Decoder decoder = Base64.getUrlDecoder();
        JsonMapper json = JsonMapper.builder().build();
        require("RS256".equals(json.readTree(decoder.decode(parts[0])).get("alg").asText()));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(trustedKeys.getPublic());
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        require(signature.verify(decoder.decode(parts[2])));
        JsonNode claims = json.readTree(decoder.decode(parts[1]));
        require("restofy-auth".equals(claims.get("iss").asText()));
        require(claims.get("aud").isArray() && claims.get("aud").size() == 1
                && "restofy-staff-api".equals(claims.get("aud").get(0).asText()));
        require("access".equals(claims.get("token_type").asText()));
        require("AUTHENTICATED".equals(claims.get("auth_state").asText()));
        require(RESTAURANT.toString().equals(claims.get("restaurant_id").asText()));
        require(Set.of("ADMIN", "WAITER", "KITCHEN").contains(claims.get("role").asText()));
        for (String name : Set.of("sub", "sid", "jti")) {
            UUID.fromString(claims.get(name).asText());
        }
        long issued = claims.get("iat").asLong();
        long expires = claims.get("exp").asLong();
        require(issued <= now.getEpochSecond() && claims.get("nbf").asLong() <= now.getEpochSecond());
        require(expires > now.getEpochSecond() && expires > issued && expires - issued <= ACCESS_SECONDS);
        return claims;
    }

    private static void require(boolean valid) {
        if (!valid) {
            throw new IllegalArgumentException("Invalid staff contract");
        }
    }
}

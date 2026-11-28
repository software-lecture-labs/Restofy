package com.restofy.menu.support;

import com.restofy.menu.domain.enums.ActorRole;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * Test-only stand-in for Auth's token issuing (lives in src/test, never packaged). It signs with a key pair generated
 * in the JVM; no real key, secret or environment value is used or written anywhere. The claims and the builder calls
 * follow Auth's {@code StaffJwtTokens.issue} for an authenticated session, so a token built here is what Menu's
 * verifier would receive from Auth, and every {@code Builder} method breaks exactly one rule of that contract.
 */
public final class TestStaffTokens {

    public static final String ISSUER = "restofy-auth";
    public static final String ORIGIN = "http://localhost:5000";
    public static final String COOKIE = "restofy_access";

    private static final KeyPair AUTH_KEYS = generate();
    private static volatile KeyPair strangerKeys;

    private TestStaffTokens() { }

    /** Base64 X.509 public key, the value {@code MENU_AUTH_STAFF_PUBLIC_KEY} would carry. */
    public static String authPublicKey() {
        return Base64.getEncoder().encodeToString(AUTH_KEYS.getPublic().getEncoded());
    }

    /** A valid, fresh access token of an authenticated session. */
    public static Builder access(UUID userId, UUID restaurantId, ActorRole role) {
        return new Builder(userId, restaurantId, role);
    }

    public static Builder admin(UUID restaurantId) {
        return access(UUID.randomUUID(), restaurantId, ActorRole.ADMIN);
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair stranger() {
        if (strangerKeys == null) {
            synchronized (TestStaffTokens.class) {
                if (strangerKeys == null) {
                    strangerKeys = generate();
                }
            }
        }
        return strangerKeys;
    }

    public static final class Builder {
        private final JwtBuilder jwt = Jwts.builder();
        private final UUID userId;
        private final UUID restaurantId;
        private final ActorRole role;
        private String issuer = ISSUER;
        private String audience = "restofy-staff-api";
        private String extraAudience;
        private String tokenType = "access";
        private String authState = "AUTHENTICATED";
        private String policy = "q5-idle-v1";
        private Instant issuedAt = Instant.now().minusSeconds(5);
        private Instant expiresAt = Instant.now().plusSeconds(290);
        private String subject;
        private String id = UUID.randomUUID().toString();
        private String sessionId = UUID.randomUUID().toString();
        private String roleClaim;
        private String restaurantClaim;
        private boolean signedByStranger;

        private Builder(UUID userId, UUID restaurantId, ActorRole role) {
            this.userId = userId;
            this.restaurantId = restaurantId;
            this.role = role;
        }

        public Builder issuer(String value) { issuer = value; return this; }
        public Builder audience(String value) { audience = value; return this; }
        public Builder alsoForAudience(String value) { extraAudience = value; return this; }
        public Builder tokenType(String value) { tokenType = value; return this; }
        public Builder authState(String value) { authState = value; return this; }
        public Builder policy(String value) { policy = value; return this; }
        public Builder subject(String value) { subject = value; return this; }
        public Builder jti(String value) { id = value; return this; }
        public Builder sid(String value) { sessionId = value; return this; }
        public Builder roleClaim(String value) { roleClaim = value; return this; }
        public Builder restaurantClaim(String value) { restaurantClaim = value; return this; }
        public Builder signedByAStranger() { signedByStranger = true; return this; }

        public Builder issuedAt(Instant value) { issuedAt = value; return this; }
        public Builder expiresAt(Instant value) { expiresAt = value; return this; }

        /** Issued {@code ageSeconds} ago for a lifetime of {@code lifetimeSeconds}. */
        public Builder lifetime(long ageSeconds, long lifetimeSeconds) {
            issuedAt = Instant.now().minusSeconds(ageSeconds);
            expiresAt = issuedAt.plusSeconds(lifetimeSeconds);
            return this;
        }

        public String build() {
            jwt.subject(subject != null ? subject : userId.toString()).issuer(issuer)
                    .audience().add(audience).and()
                    .issuedAt(Date.from(issuedAt)).notBefore(Date.from(issuedAt)).expiration(Date.from(expiresAt));
            if (extraAudience != null) {
                jwt.audience().add(extraAudience);
            }
            if (id != null) {
                jwt.id(id);
            }
            if (sessionId != null) {
                jwt.claim("sid", sessionId);
            }
            jwt.claim("role", roleClaim != null ? roleClaim : role.name())
                    .claim("auth_state", authState)
                    .claim("restaurant_id", restaurantClaim != null ? restaurantClaim : restaurantId.toString())
                    .claim("token_type", tokenType);
            if (policy != null) {
                jwt.claim("auth_policy", policy);
            }
            KeyPair keys = signedByStranger ? stranger() : AUTH_KEYS;
            return jwt.signWith(keys.getPrivate(), Jwts.SIG.RS256).compact();
        }

        /** An unsigned token ({@code alg=none}) with otherwise valid claims. */
        public String buildUnsigned() {
            return Jwts.builder().subject(userId.toString()).issuer(issuer).audience().add(audience).and()
                    .issuedAt(Date.from(issuedAt)).notBefore(Date.from(issuedAt)).expiration(Date.from(expiresAt))
                    .id(id).claim("sid", sessionId).claim("role", role.name()).claim("auth_state", authState)
                    .claim("restaurant_id", restaurantId.toString()).claim("token_type", tokenType)
                    .claim("auth_policy", policy).compact();
        }

        /** HS256 with Auth's PUBLIC key bytes as the secret: the classic algorithm-confusion token. */
        public String buildWithPublicKeyAsHmacSecret() {
            var secret = new javax.crypto.spec.SecretKeySpec(AUTH_KEYS.getPublic().getEncoded(), "HmacSHA256");
            return Jwts.builder().subject(userId.toString()).issuer(issuer).audience().add(audience).and()
                    .issuedAt(Date.from(issuedAt)).notBefore(Date.from(issuedAt)).expiration(Date.from(expiresAt))
                    .id(id).claim("sid", sessionId).claim("role", role.name()).claim("auth_state", authState)
                    .claim("restaurant_id", restaurantId.toString()).claim("token_type", tokenType)
                    .claim("auth_policy", policy).signWith(secret, Jwts.SIG.HS256).compact();
        }
    }
}

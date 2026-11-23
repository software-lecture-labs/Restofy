package com.restofy.menu.infrastructure.security;

import com.restofy.menu.application.security.AuthenticatedActor;
import com.restofy.menu.domain.enums.ActorRole;
import com.restofy.menu.support.TestStaffTokens;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every rule of Auth's staff-v1 access token, one at a time, against tokens built like Auth builds them. A token
 * that breaks exactly one rule must be refused; the actor of a valid token carries nothing but its signed claims.
 */
class StaffJwtVerifierTests {

    private static final UUID USER = UUID.fromString("11111111-0000-0000-0000-000000000001");
    private static final UUID RESTAURANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    private final StaffJwtVerifier verifier = new StaffJwtVerifier(
            new StaffAuthProperties(TestStaffTokens.authPublicKey(), TestStaffTokens.ISSUER, List.of()), Clock.systemUTC());

    private TestStaffTokens.Builder valid() {
        return TestStaffTokens.access(USER, RESTAURANT, ActorRole.ADMIN);
    }

    private void assertRefused(String token) {
        assertEquals(Optional.empty(), verifier.verify(token));
    }

    @Test
    void aValidTokenYieldsExactlyItsSignedClaims() {
        for (ActorRole role : ActorRole.values()) {
            String token = TestStaffTokens.access(USER, RESTAURANT, role).build();
            assertEquals(Optional.of(new AuthenticatedActor(USER, RESTAURANT, role)), verifier.verify(token));
        }
    }

    @Test
    void theRestaurantAndTheUserComeFromTheClaimsNotFromAnythingElse() {
        UUID other = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
        AuthenticatedActor actor = verifier.verify(TestStaffTokens.access(USER, other, ActorRole.ADMIN).build()).orElseThrow();
        assertEquals(other, actor.restaurantId());
        assertEquals(USER, actor.userId());
    }

    @Test
    void aTokenSignedByAnotherKeyIsRefused() {
        assertRefused(valid().signedByAStranger().build());
    }

    @Test
    void anUnsignedTokenAndTheAlgorithmConfusionTokenAreRefused() {
        assertRefused(valid().buildUnsigned());
        assertRefused(valid().buildWithPublicKeyAsHmacSecret());
    }

    @Test
    void aTamperedPayloadIsRefused() {
        String[] parts = valid().build().split("\\.");
        String forged = TestStaffTokens.access(USER, UUID.randomUUID(), ActorRole.ADMIN).build().split("\\.")[1];
        assertRefused(parts[0] + "." + forged + "." + parts[2]);
    }

    @Test
    void anExpiredTokenIsRefused() {
        assertRefused(valid().lifetime(400, 300).build());
        assertRefused(valid().expiresAt(Instant.now().minusSeconds(1)).issuedAt(Instant.now().minusSeconds(120)).build());
    }

    @Test
    void aTokenIssuedInTheFutureIsRefused() {
        assertRefused(valid().issuedAt(Instant.now().plusSeconds(60)).expiresAt(Instant.now().plusSeconds(300)).build());
    }

    @Test
    void aTokenLivingLongerThanAuthsThreeHundredSecondsIsRefused() {
        assertRefused(valid().lifetime(0, 301).build());
        assertRefused(valid().lifetime(5, 900).build());
        assertTrue(verifier.verify(valid().lifetime(5, 300).build()).isPresent(), "exactly 300 seconds is Auth's lifetime");
    }

    @Test
    void aNonPositiveLifetimeIsRefused() {
        Instant now = Instant.now().minusSeconds(5);
        assertRefused(valid().issuedAt(now).expiresAt(now).build());
        assertRefused(valid().issuedAt(now).expiresAt(now.minusSeconds(10)).build());
    }

    @Test
    void theClockDecidesNotTheTokensOwnClaims() {
        String token = valid().build();
        Clock later = Clock.fixed(Instant.now().plusSeconds(600), ZoneOffset.UTC);
        StaffJwtVerifier afterExpiry = new StaffJwtVerifier(
                new StaffAuthProperties(TestStaffTokens.authPublicKey(), TestStaffTokens.ISSUER, List.of()), later);
        assertTrue(verifier.verify(token).isPresent());
        assertEquals(Optional.empty(), afterExpiry.verify(token));
    }

    @Test
    void aWrongIssuerOrAudienceIsRefused() {
        assertRefused(valid().issuer("someone-else").build());
        assertRefused(valid().audience("restofy-auth-mfa").build());
        assertRefused(valid().audience("restofy-mail").build());
        assertRefused(valid().audience("restofy-qr-revocation").build());
        assertRefused(valid().alsoForAudience("restofy-auth-mfa").build());
        assertRefused(valid().alsoForAudience("restofy-mail").build());
    }

    @Test
    void aPendingMfaAndANonAccessTokenIsNeverAStaffCredential() {
        assertRefused(valid().tokenType("mfa_pending").authState("MFA_PENDING").audience("restofy-auth-mfa").build());
        assertRefused(valid().tokenType("mfa_pending").build());
        assertRefused(valid().authState("MFA_PENDING").build());
        assertRefused(valid().tokenType("refresh").build());
        assertRefused(valid().tokenType("service").build());
    }

    @Test
    void theSessionPolicyOfAuthIsRequired() {
        assertRefused(valid().policy(null).build());
        assertRefused(valid().policy("legacy").build());
    }

    @Test
    void missingOrMalformedIdentifiersAreRefused() {
        assertRefused(valid().subject("not-a-uuid").build());
        assertRefused(valid().subject(" ").build());
        assertRefused(valid().subject("zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz").build());
        assertRefused(valid().restaurantClaim("zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz").build());
        assertRefused(valid().subject(USER + "-").build());
        assertRefused(valid().restaurantClaim("not-a-uuid").build());
        assertRefused(valid().restaurantClaim("00000000-0000-0000-0000-000000000000").build());
        assertRefused(valid().jti(null).build());
        assertRefused(valid().jti("x").build());
        assertRefused(valid().sid(null).build());
        assertRefused(valid().sid("x").build());
    }

    @Test
    void anUnknownOrMissingRoleIsRefused() {
        assertRefused(valid().roleClaim("OWNER").build());
        assertRefused(valid().roleClaim("admin").build());
        assertRefused(valid().roleClaim("").build());
        assertRefused(valid().roleClaim("PLATFORM_ADMIN").build());
    }

    @Test
    void garbageAndOversizedInputIsRefusedWithoutAnException() {
        assertRefused(null);
        assertRefused("");
        assertRefused("not.a.jwt!");
        assertRefused("only-one-part");
        assertRefused("a.b");
        assertRefused("a.b.c");
        assertRefused(valid().build() + "x");
        assertRefused("A".repeat(5000));
        assertRefused(valid().build() + ".extra");
    }

    @Test
    void withoutAConfiguredKeyNothingAuthenticatesEvenAPerfectToken() {
        StaffJwtVerifier unconfigured = new StaffJwtVerifier(new StaffAuthProperties("", "", List.of()), Clock.systemUTC());
        assertFalse(unconfigured.enabled());
        assertEquals(Optional.empty(), unconfigured.verify(valid().build()));
        assertEquals(Optional.empty(), unconfigured.verify(null));
    }

    @Test
    void aVerifierForAnotherIssuerRefusesEveryTokenOfThisOne() {
        StaffJwtVerifier other = new StaffJwtVerifier(
                new StaffAuthProperties(TestStaffTokens.authPublicKey(), "another-auth", List.of()), Clock.systemUTC());
        assertEquals(Optional.empty(), other.verify(valid().build()));
    }
}

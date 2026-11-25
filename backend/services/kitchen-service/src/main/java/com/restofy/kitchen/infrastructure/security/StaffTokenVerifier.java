package com.restofy.kitchen.infrastructure.security;

import com.restofy.kitchen.infrastructure.config.KitchenAuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The local half of staff authentication: proves that the {@code restofy_access} value is an access token Auth
 * signed for staff APIs. It accepts only RS256 with the configured public key, the configured issuer, exactly the
 * {@code restofy-staff-api} audience, an access token of an {@code AUTHENTICATED} session under the
 * {@code q5-idle-v1} policy, {@code iat} = {@code nbf} not later than a few seconds from now, an unexpired
 * {@code exp} within Auth's access lifetime, and well-formed user, restaurant, session and token ids with a known
 * role. MFA-pending, refresh, guest and service tokens are not staff tokens.
 *
 * <p>This alone does NOT prove the session is still valid (logout, disabled account, changed role); that is the
 * job of {@link StaffSessionCheck}. Without a configured public key nothing verifies.
 */
public class StaffTokenVerifier {

	static final String AUDIENCE = "restofy-staff-api";
	private static final int MAX_TOKEN_LENGTH = 4096;
	/** How far in the future {@code iat}/{@code nbf} may be (clock difference with Auth). Expiry gets no leeway. */
	private static final long START_CLOCK_SKEW_SECONDS = 3;
	private static final int UUID_LENGTH = 36;
	private static final UUID ZERO = new UUID(0, 0);
	private static final Pattern COMPACT_JWS = Pattern.compile("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

	private final JwtParser parser;
	private final Clock clock;
	private final long accessTtlSeconds;

	public StaffTokenVerifier(KitchenAuthProperties properties, Clock clock) {
		this.clock = clock;
		this.accessTtlSeconds = properties.accessTtlSeconds();
		this.parser = !properties.staffAuthenticationEnabled() ? null
				: Jwts.parser().verifyWith(properties.rsaPublicKey())
						.sig().clear().add(Jwts.SIG.RS256).and()
						.clock(JwtClock.of(clock))
						.clockSkewSeconds(START_CLOCK_SKEW_SECONDS)
						.requireIssuer(properties.issuer())
						.require("token_type", "access")
						.require("auth_state", "AUTHENTICATED")
						.require("auth_policy", "q5-idle-v1")
						.build();
	}

	public Optional<VerifiedStaffToken> verify(String token) {
		if (parser == null || token == null || token.length() > MAX_TOKEN_LENGTH || !COMPACT_JWS.matcher(token).matches()) {
			return Optional.empty();
		}
		try {
			return identity(parser.parseSignedClaims(token).getPayload());
		} catch (JwtException | IllegalArgumentException _) {
			return Optional.empty();
		}
	}

	private Optional<VerifiedStaffToken> identity(Claims claims) {
		if (!Set.of(AUDIENCE).equals(claims.getAudience()) || !validLifetime(claims)) {
			return Optional.empty();
		}
		UUID tokenId = identifier(claims.getId());
		UUID sessionId = identifier(claims.get("sid", String.class));
		UUID userId = identifier(claims.getSubject());
		UUID restaurantId = identifier(claims.get("restaurant_id", String.class));
		StaffRole role = role(claims.get("role", String.class));
		if (tokenId == null || sessionId == null || userId == null || restaurantId == null || role == null
				|| ZERO.equals(restaurantId)) {
			return Optional.empty();
		}
		return Optional.of(new VerifiedStaffToken(new KitchenPrincipal(userId, restaurantId), role,
				claims.getExpiration().toInstant()));
	}

	/** Checked here as well because jjwt applies its clock skew to the expiry too. */
	private boolean validLifetime(Claims claims) {
		if (claims.getIssuedAt() == null || claims.getExpiration() == null || claims.getNotBefore() == null) {
			return false;
		}
		Instant issuedAt = claims.getIssuedAt().toInstant();
		Instant expiry = claims.getExpiration().toInstant();
		Instant now = clock.instant();
		return claims.getNotBefore().toInstant().equals(issuedAt)
				&& !issuedAt.isAfter(now.plusSeconds(START_CLOCK_SKEW_SECONDS))
				&& expiry.isAfter(now) && expiry.isAfter(issuedAt)
				&& !expiry.isAfter(issuedAt.plusSeconds(accessTtlSeconds));
	}

	private static UUID identifier(String value) {
		if (value == null || value.length() != UUID_LENGTH) {
			return null;
		}
		UUID id = UUID.fromString(value);
		return id.toString().equalsIgnoreCase(value) ? id : null;
	}

	private static StaffRole role(String value) {
		for (StaffRole role : StaffRole.values()) {
			if (role.name().equals(value)) {
				return role;
			}
		}
		return null;
	}

	/** The identity a token proves. The token text itself is deliberately not part of it. */
	public record VerifiedStaffToken(KitchenPrincipal principal, StaffRole role, Instant expiresAt) {
	}
}

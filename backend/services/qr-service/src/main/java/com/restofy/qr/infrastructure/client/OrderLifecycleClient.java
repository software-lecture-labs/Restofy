package com.restofy.qr.infrastructure.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.restofy.qr.application.port.OrderSessionAccess;
import com.restofy.qr.domain.OrderSessionState;
import com.restofy.qr.domain.OrderSessionState.Knowledge;
import com.restofy.qr.domain.SessionTransition.*;
import com.restofy.qr.domain.exception.SessionClosureUnavailableException;
import com.restofy.qr.infrastructure.security.JwtDates;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@Profile("qr-order")
public class OrderLifecycleClient extends OrderSessionAccess {
    private static final String PREPARATIONS = "/closure-preparations";
    private static final int MAX_IDLE_CANDIDATES = 500;

    private final URI base;
    private final UUID tenant;
    private final String keyId;
    private final RSAPrivateKey key;
    private final Clock clock;
    private final JsonMapper json;
    private final RestClient http;

    public OrderLifecycleClient(@Value("${ORDER_SERVICE_URL}") URI base,
                                @Value("${AUTH_RESTAURANT_ID}") UUID tenant,
                                @Value("${QR_ORDER_SERVICE_KEY_ID}") String keyId,
                                @Value("${QR_ORDER_SERVICE_PRIVATE_KEY}") String privateKey,
                                @Value("${REST_CLIENT_CONNECT_TIMEOUT_MS:3000}") long connectMs,
                                @Value("${REST_CLIENT_READ_TIMEOUT_MS:5000}") long readMs,
                                Environment environment, Clock clock, JsonMapper json) {
        this.base = validateBase(base, environment.acceptsProfiles(Profiles.of("(dev | test) & !prod")));
        this.tenant = tenant;
        if (!keyId.matches("[A-Za-z0-9._-]{1,64}") || connectMs <= 0 || readMs <= 0) {
            throw new IllegalStateException("Invalid QR Order key ID or HTTP timeout");
        }
        this.keyId = keyId;
        this.key = readKey(privateKey);
        this.clock = clock;
        this.json = json;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectMs)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofMillis(readMs));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public OrderSessionState inspect(UUID restaurantId, UUID sessionId) {
        try {
            var response = send(HttpMethod.GET, sessionPath(sessionId) + "/order-state?restaurantId=" + restaurantId,
                    restaurantId, null, StateResponse.class);
            var state = new OrderSessionState(response.restaurantId(), response.tableSessionId(), response.knowledge(),
                    response.revision(), response.verifiedAt(), response.validUntil(), response.protectedGuests(),
                    response.cancellationReleases(), response.mainCancellationAt());
            if (state.usableFor(restaurantId, sessionId, clock.instant())) { return state; }
        } catch (RuntimeException _) {
            // An unavailable or malformed read is never evidence of an empty session.
        }
        return OrderSessionState.unknown(restaurantId, sessionId);
    }

    @Override
    public Authority prepare(Command command) {
        return authority(command, closure(HttpMethod.POST, resource(command), command, prepareBody(command)));
    }

    @Override
    public Authority status(Command command) {
        return authority(command, closure(HttpMethod.GET, operation(command) + "?restaurantId="
                + command.snapshot().restaurantId() + "&commandDigest=" + command.digest(), command, null));
    }

    @Override
    public Confirmation acknowledge(Command command, LocalResult result) {
        var snapshot = command.snapshot();
        if (!command.operationId().equals(result.operationId()) || !snapshot.restaurantId().equals(result.restaurantId())
                || !snapshot.sessionId().equals(result.sessionId()) || !snapshot.tableId().equals(result.tableId())) {
            throw unavailable();
        }
        var receipt = new Receipt(result.effect(), result.sessionVersion(), result.tableVersion(),
                result.completedAt(), result.expiredGuests());
        return confirm(command, "CLOSED", receipt);
    }

    @Override
    public Confirmation abort(Command command) {
        return confirm(command, "NOT_CLOSED", null);
    }

    private Confirmation confirm(Command command, String outcome, Receipt receipt) {
        var response = closure(HttpMethod.POST, operation(command) + "/confirmation", command,
                new ConfirmRequest(command.snapshot().restaurantId(), outcome, command.digest(), receipt));
        try {
            return new Confirmation(response.operationId(), response.restaurantId(), response.tableSessionId(),
                    response.purpose(), response.commandDigest(), response.state(), response.effect(),
                    response.sourceRevision(), response.verifiedAt(), response.complete(), response.scopeDrift(),
                    response.driftBlockers());
        } catch (RuntimeException _) {
            throw unavailable();
        }
    }

    private ClosureResponse closure(HttpMethod method, String target, Command command, Object body) {
        var response = send(method, target, command.snapshot().restaurantId(), body, ClosureResponse.class);
        if (!command.operationId().equals(response.operationId())
                || !command.snapshot().restaurantId().equals(response.restaurantId())
                || !command.snapshot().sessionId().equals(response.tableSessionId())
                || !command.digest().equals(response.commandDigest()) || response.state() == null
                || !(response.purpose() == command.purpose() || response.purpose() == null
                        && response.state() == SourceState.NOT_STARTED)) {
            throw unavailable();
        }
        return response;
    }

    private Authority authority(Command command, ClosureResponse response) {
        try {
            var candidates = idleCandidates(command).stream().map(IdleGuest::guestSessionId).collect(Collectors.toSet());
            if ((response.sourceStatus() == SourceStatus.AUTHORISED && response.state() != SourceState.PREPARED)
                    || (response.sourceStatus() == SourceStatus.NOT_STARTED && response.state() != SourceState.NOT_STARTED)
                    || !candidates.containsAll(response.idleGuestIds())) {
                throw unavailable();
            }
            return new Authority(command, response.sourceStatus(), response.effect(), response.sourceRevision(),
                    response.verifiedAt(), response.validUntil(), response.complete(), response.checkoutFenced(),
                    response.idleGuestIds());
        } catch (RuntimeException _) {
            throw unavailable();
        }
    }

    private static PrepareRequest prepareBody(Command command) {
        var snapshot = command.snapshot();
        boolean idle = command.purpose() == Purpose.IDLE;
        return new PrepareRequest(snapshot.restaurantId(), command.operationId(), command.purpose(), command.digest(),
                idle ? command.requestedAt() : null, idle ? snapshot.idleExpiresAt() : null,
                idle ? idleCandidates(command) : null);
    }

    private static List<IdleGuest> idleCandidates(Command command) {
        if (command.purpose() != Purpose.IDLE) { return List.of(); }
        return command.snapshot().guests().entrySet().stream()
                .filter(entry -> entry.getValue().expiresAt() != null
                        && !entry.getValue().expiresAt().isAfter(command.requestedAt()))
                .limit(MAX_IDLE_CANDIDATES)
                .map(entry -> new IdleGuest(entry.getKey(), entry.getValue().expiresAt())).toList();
    }

    private <T> T send(HttpMethod method, String target, UUID restaurantId, Object body, Class<T> type) {
        if (!tenant.equals(restaurantId)) { throw unavailable(); }
        try {
            byte[] bytes = body == null ? new byte[0] : json.writeValueAsBytes(body);
            var request = http.method(method).uri(base.resolve(target)).accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Service " + sign(method, target, bytes));
            if (body != null) { request.contentType(MediaType.APPLICATION_JSON).body(bytes); }
            return Objects.requireNonNull(request.retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (sent, response) -> { throw unavailable(); })
                    .body(type));
        } catch (RuntimeException | GeneralSecurityException _) {
            // Keep the durable operation pending. Never use transport failure as a source decision.
            throw unavailable();
        }
    }

    private String sign(HttpMethod method, String target, byte[] body) throws GeneralSecurityException {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        return Jwts.builder().header().keyId(keyId).and().issuer("restofy-qr").subject("qr-service")
                .audience().add("restofy-order").and().id(UUID.randomUUID().toString())
                .issuedAt(JwtDates.from(now)).notBefore(JwtDates.from(now)).expiration(JwtDates.from(now.plusSeconds(30)))
                .claim("token_type", "service").claim("scope", "table-session:lifecycle")
                .claim("restaurant_id", tenant.toString()).claim("method", method.name())
                .claim("target", target).claim("body_sha256", digest).signWith(key, Jwts.SIG.RS256).compact();
    }

    private static URI validateBase(URI base, boolean local) {
        boolean loopback = Set.of("localhost", "127.0.0.1", "[::1]").contains(Objects.toString(base.getHost(), ""));
        if (base.getHost() == null || base.getRawUserInfo() != null || base.getRawQuery() != null
                || base.getRawFragment() != null || !Set.of("", "/").contains(Objects.toString(base.getRawPath(), ""))
                || !("https".equalsIgnoreCase(base.getScheme())
                        || local && loopback && "http".equalsIgnoreCase(base.getScheme()))) {
            throw new IllegalStateException("ORDER_SERVICE_URL must be an HTTPS origin (loopback HTTP only in dev/test)");
        }
        return base;
    }

    private static RSAPrivateKey readKey(String encoded) {
        try {
            var candidate = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)));
            if (candidate instanceof RSAPrivateKey rsa && rsa.getModulus().bitLength() >= 2048) { return rsa; }
        } catch (GeneralSecurityException | IllegalArgumentException _) {
            // Do not expose secret input or parser details in startup errors.
        }
        throw new IllegalStateException("Invalid QR Order RSA signing key");
    }

    private static String sessionPath(UUID sessionId) {
        return UriComponentsBuilder.newInstance().pathSegment("api", "v1", "internal", "table-sessions", sessionId.toString())
                .build().getPath();
    }

    private static String resource(Command command) { return sessionPath(command.snapshot().sessionId()) + PREPARATIONS; }
    private static String operation(Command command) { return resource(command) + "/" + command.operationId(); }
    private static SessionClosureUnavailableException unavailable() { return new SessionClosureUnavailableException(); }

    record IdleGuest(UUID guestSessionId, Instant idleExpiresAt) { }
    record PrepareRequest(UUID restaurantId, UUID operationId, Purpose purpose, String commandDigest,
                          Instant requestedAt, Instant mainIdleExpiresAt, List<IdleGuest> guests) { }
    record Receipt(Effect effect, long sessionVersion, long tableVersion, Instant completedAt, int expiredGuests) { }
    record ConfirmRequest(UUID restaurantId, String outcome, String commandDigest, Receipt receipt) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StateResponse(UUID restaurantId, UUID tableSessionId, Knowledge knowledge, long revision, Instant verifiedAt,
                         Instant validUntil, Set<UUID> protectedGuests, Map<UUID, Instant> cancellationReleases,
                         Instant mainCancellationAt) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ClosureResponse(UUID operationId, UUID restaurantId, UUID tableSessionId, Purpose purpose, String commandDigest,
                           SourceState state, SourceStatus sourceStatus, Effect effect, long sourceRevision, Instant verifiedAt,
                           Instant validUntil, boolean complete, boolean checkoutFenced, Set<UUID> idleGuestIds,
                           Boolean scopeDrift, List<String> driftBlockers) { }
}

package com.restofy.order.infrastructure.client;

import com.restofy.order.application.port.GuestSessionContext;
import com.restofy.order.application.port.GuestSessionContext.SessionState;
import com.restofy.order.application.port.QrSessionPort;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Profile("order-qr")
public class QrSessionHttpAdapter implements QrSessionPort {

    @SuppressWarnings("java:S1075") // The signed paths belong to the QR wire contract. Only the service origin varies.
    private static final String BASE_PATH = "/api/v1/internal/qr";
    private static final Pattern GUEST_TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final byte[] EMPTY_BODY = {};
    private static final int MAX_BODY_BYTES = 16 * 1024;
    private static final Duration MAX_STATE_AGE = Duration.ofSeconds(15);
    private static final Duration MAX_FUTURE_SKEW = Duration.ofSeconds(2);

    private final URI base;
    private final OrderQrRequestSigner signer;
    private final RestClient http;
    private final JsonMapper json;
    private final Clock clock;

    QrSessionHttpAdapter(@Value("${QR_SERVICE_URL:}") String base, OrderQrRequestSigner signer,
                         RestClient.Builder client, JsonMapper json, Clock clock) {
        this.base = httpsOrigin(base, "QR_SERVICE_URL", "order-qr");
        this.signer = signer;
        this.http = client.build();
        this.json = json;
        this.clock = clock;
    }

    @Override
    public Optional<GuestSessionContext> validateGuestSession(String guestSessionToken) {
        if (guestSessionToken == null || !GUEST_TOKEN.matcher(guestSessionToken).matches()) {
            return Optional.empty();
        }
        return read(HttpMethod.POST, BASE_PATH + "/guest-sessions/validate",
                json.writeValueAsBytes(new GuestToken(guestSessionToken)), GuestResponse.class)
                .map(response -> {
                    if (!signer.restaurantId().equals(response.restaurantId()) || response.tableId() == null
                            || response.tableSessionId() == null || response.guestSessionId() == null
                            || !SessionState.ACTIVE.name().equals(response.state()) || !fresh(response.checkedAt())) {
                        throw unavailable();
                    }
                    return new GuestSessionContext(response.restaurantId(), response.tableId(), response.tableSessionId(),
                            response.guestSessionId(), SessionState.ACTIVE);
                });
    }

    @Override
    public Optional<SessionState> currentSessionState(UUID restaurantId, UUID tableSessionId, UUID guestSessionId) {
        if (!signer.restaurantId().equals(restaurantId)) {
            return Optional.empty();
        }
        String target = BASE_PATH + "/table-sessions/" + tableSessionId + "/guests/" + guestSessionId + "/state";
        return read(HttpMethod.GET, target, EMPTY_BODY, StateResponse.class).map(response -> {
            if (!fresh(response.checkedAt()) || response.state() == null) {
                throw unavailable();
            }
            try {
                return SessionState.valueOf(response.state());
            } catch (IllegalArgumentException _) {
                throw unavailable();
            }
        });
    }

    public UUID restaurantId() {
        return signer.restaurantId();
    }

    /** Returns the peer status, or throws on an unavailable transport. The dispatcher owns retry policy. */
    public int sendCartActivity(UUID restaurantId, byte[] body) {
        if (!signer.restaurantId().equals(restaurantId) || body.length == 0 || body.length > MAX_BODY_BYTES) {
            return 400;
        }
        try {
            return request(HttpMethod.POST, BASE_PATH + "/events/cart-activity", body)
                    .exchange((sent, response) -> response.getStatusCode().value());
        } catch (RestClientException _) {
            throw unavailable();
        }
    }

    private <T> Optional<T> read(HttpMethod method, String target, byte[] body, Class<T> type) {
        try {
            return request(method, target, body).exchange((sent, response) -> {
                int status = response.getStatusCode().value();
                if (status == 404 && invalidGuestProblem(response)) {
                    return Optional.empty();
                }
                if (status != 200) {
                    throw unavailable();
                }
                T result = response.bodyTo(type);
                if (result == null) {
                    throw unavailable();
                }
                return Optional.of(result);
            });
        } catch (RestClientException | InvalidMediaTypeException _) {
            throw unavailable();
        }
    }

    private static boolean invalidGuestProblem(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        MediaType mediaType = response.getHeaders().getContentType();
        if (mediaType == null || !MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(mediaType)) {
            return false;
        }
        JsonNode problem = response.bodyTo(JsonNode.class);
        return problem != null && problem.path("status").isInt() && problem.path("status").asInt() == 404
                && "Guest session is unavailable.".equals(problem.path("detail").asString());
    }

    private boolean fresh(String timestamp) {
        if (timestamp == null) {
            return false;
        }
        Instant now = clock.instant();
        try {
            Instant checkedAt = Instant.parse(timestamp);
            return !checkedAt.isBefore(now.minus(MAX_STATE_AGE)) && !checkedAt.isAfter(now.plus(MAX_FUTURE_SKEW));
        } catch (DateTimeParseException _) {
            return false;
        }
    }

    private RestClient.RequestHeadersSpec<?> request(HttpMethod method, String target, byte[] body) {
        var request = http.method(method).uri(base.resolve(target)).accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, signer.authorization(method, target, body));
        return body.length == 0 ? request : request.contentType(MediaType.APPLICATION_JSON).body(body);
    }

    static URI httpsOrigin(String value, String property, String profile) {
        try {
            URI candidate = URI.create(value);
            if ("https".equalsIgnoreCase(candidate.getScheme()) && candidate.getHost() != null
                    && (candidate.getPort() == -1 || candidate.getPort() > 0 && candidate.getPort() <= 65535)
                    && candidate.getRawUserInfo() == null && candidate.getRawQuery() == null
                    && candidate.getRawFragment() == null
                    && (candidate.getRawPath().isEmpty() || "/".equals(candidate.getRawPath()))) {
                return candidate;
            }
        } catch (IllegalArgumentException _) {
            // Do not include an invalid URL that could contain credentials in a startup error.
        }
        throw new IllegalStateException(property + " must be an HTTPS origin for " + profile);
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "QR Service is currently unavailable");
    }

    private record GuestToken(String guestToken) {
        @Override
        public String toString() {
            return "QR guest validation request";
        }
    }

    private record GuestResponse(UUID restaurantId, UUID tableId, UUID tableSessionId, UUID guestSessionId,
                                 String state, String checkedAt) {
    }

    private record StateResponse(String state, String checkedAt) {
    }
}

package com.restofy.menu.infrastructure.client;

import com.restofy.menu.application.security.GuestSessionVerification;
import com.restofy.menu.application.security.GuestSessionVerifier;
import com.restofy.menu.application.security.GuestToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The real {@link GuestSessionVerifier}: asks QR which restaurant a guest credential belongs to (QR-REQUEST-01, PR #65).
 *
 * <p>{@code POST /api/v1/internal/qr/menu/guest-sessions/validate}, body {@code {"guestToken":"<43 characters>"}},
 * {@code Authorization: Service <JWT>} signed by Menu's own key ({@link QrRequestSigner}). Nothing else is sent: no cookie,
 * no origin, no client header, no restaurant. QR answers with the restaurant of the guest session; that answer is the only
 * source of the restaurant of a customer read.
 *
 * <p>Mapping, exactly the {@link GuestSessionVerifier} contract:
 * <ul>
 *   <li>{@code 200} with {@code state=ACTIVE} and a real restaurant id: {@link GuestSessionVerification.Verified}.</li>
 *   <li>{@code 404} <b>with</b> the problem code {@code GUEST_SESSION_UNAVAILABLE}: {@link GuestSessionVerification.Rejected}
 *       (unknown, closed, expired or foreign guest). A bare {@code 404} (a wrong route, a gateway, a QR profile that is off)
 *       says nothing about the guest and is "unavailable".</li>
 *   <li>Everything else is {@link GuestSessionVerification.Unavailable}: {@code 400/401/403/429/5xx}, a redirect (never
 *       followed), a timeout, a connection problem, a body that lacks the documented fields or is not JSON (unknown extra fields are ignored). QR's verdict is never
 *       guessed, never retried and never cached.</li>
 * </ul>
 *
 * <p>The guest token and the credential are secrets: they appear in no log line, no exception message and no answer; a
 * failure logs only the class of the problem.
 */
public final class QrGuestSessionVerifier implements GuestSessionVerifier, AutoCloseable {

    static final String VALIDATE_PATH = "/api/v1/internal/qr/menu/guest-sessions/validate";
    static final String UNAVAILABLE_CODE = "GUEST_SESSION_UNAVAILABLE";
    static final int MAX_RESPONSE_BYTES = 4096;

    private static final Logger LOG = LoggerFactory.getLogger(QrGuestSessionVerifier.class);
    private static final UUID ZERO = new UUID(0, 0);

    private final URI endpoint;
    private final QrRequestSigner signer;
    private final HttpClient httpClient;
    private final RestClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    QrGuestSessionVerifier(URI origin, QrRequestSigner signer, HttpClient httpClient, RestClient http) {
        this.endpoint = origin.resolve(VALIDATE_PATH);
        this.signer = signer;
        this.httpClient = httpClient;
        this.http = http;
    }

    @Override
    public GuestSessionVerification verify(GuestToken token) {
        try {
            // The token is 43 URL-safe characters (GuestToken): it needs no JSON escaping. The signed digest covers these exact bytes.
            byte[] body = ("{\"guestToken\":\"" + token.value() + "\"}").getBytes(StandardCharsets.UTF_8);
            String authorization = signer.authorization("POST", VALIDATE_PATH, body);
            return http.post().uri(endpoint).accept(MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, authorization).body(body)
                    .exchange((request, response) -> interpret(response.getStatusCode().value(),
                            response.getHeaders().getContentType(), response.getBody()));
        } catch (RuntimeException e) {
            // Class only: a message can carry the address or, for a parser, part of the body.
            LOG.warn("QR guest session verification failed ({}).", e.getClass().getName());
            return new GuestSessionVerification.Unavailable();
        }
    }

    private GuestSessionVerification interpret(int status, MediaType contentType, InputStream stream) throws IOException {
        if (status != 200 && status != 404) {
            return unavailable("status");
        }
        byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
        if (bytes.length > MAX_RESPONSE_BYTES || contentType == null) {
            return unavailable("body");
        }
        JsonNode node = json.readTree(bytes);
        if (status == 404) {
            boolean problem = MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(contentType);
            return problem && node.isObject() && UNAVAILABLE_CODE.equals(text(node, "code"))
                    ? new GuestSessionVerification.Rejected() : unavailable("not-found");
        }
        if (!MediaType.APPLICATION_JSON.isCompatibleWith(contentType) || !node.isObject() || !"ACTIVE".equals(text(node, "state"))) {
            return unavailable("answer");
        }
        UUID restaurant = canonicalUuid(text(node, "restaurantId"));
        return restaurant == null || ZERO.equals(restaurant) ? unavailable("restaurant")
                : new GuestSessionVerification.Verified(restaurant);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }

    private static UUID canonicalUuid(String value) {
        if (value == null || value.length() != 36) {
            return null;
        }
        try {
            UUID id = UUID.fromString(value);
            return id.toString().equals(value) ? id : null;
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    private static GuestSessionVerification.Unavailable unavailable(String reason) {
        LOG.warn("QR guest session verification gave an unusable answer ({}).", reason);
        return new GuestSessionVerification.Unavailable();
    }

    @Override
    public void close() {
        httpClient.close();
    }
}

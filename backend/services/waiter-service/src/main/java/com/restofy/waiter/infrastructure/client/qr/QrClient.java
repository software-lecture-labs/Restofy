package com.restofy.waiter.infrastructure.client.qr;

import com.restofy.waiter.domain.enums.ClosureBlocker;
import com.restofy.waiter.domain.enums.TableOperationalStatus;
import com.restofy.waiter.domain.exception.DependencyException;
import com.restofy.waiter.domain.exception.DependencyFailure;
import com.restofy.waiter.domain.exception.SnapshotLimitExceededException;
import com.restofy.waiter.infrastructure.client.DownstreamCalls;
import com.restofy.waiter.infrastructure.config.ServiceUrlProperties;
import com.restofy.waiter.infrastructure.security.StaffAccessToken;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Adapter for the QR service, the owner of tables and table sessions. Everything it reads is checked
 * against the caller's restaurant, so data of another tenant is never passed on. It calls QR directly, never
 * through the Gateway. Besides the reads it carries the two table commands, closing a main session and marking a
 * table cleaned: QR alone decides them, and nothing here sends a command again on its own.
 *
 * <p>Every call uses the shared {@code /api/v1/internal} prefix and carries Waiter's own service identity
 * ({@link WaiterQrRequestSigner}), one scope per route family: {@code qr:guest-validate} (QR PR #41),
 * {@code qr:table-read}, {@code qr:session-close} and {@code qr:table-clean} (QR PR #51). The close and clean
 * routes, including their status reads, also need the staff member's access token in {@code X-Staff-Token}
 * ({@link StaffAccessToken}), bound to the credential by digest. While no service key is configured nothing is signed
 * and QR answers 401. The close command follows ADR-QR-001 K11; QR PR #51 implements the table reads and the cleaning
 * command with the same paths and payloads Waiter already used. Not verified against a running QR yet.
 *
 * <p>QR's answers are read with unknown fields ignored, so an additive field of QR does not turn into an unknown
 * outcome; identifiers, enum values, states and the cross-checks below are still enforced. Incoming requests of
 * Waiter keep rejecting unknown fields.
 */
@Component
public class QrClient {

    public static final String DEPENDENCY = "qr";
    /** Largest restaurant the table snapshot supports; bigger restaurants fail instead of being cut short. */
    public static final int MAX_TABLES = 500;
    static final int PAGE_SIZE = 200;
    private static final String RESTAURANT_ID = "restaurantId";
    /** QR-INT-02 guest validation, shared with Order (QR's OrderGuestSessionController). */
    @SuppressWarnings("java:S1075") // The path belongs to QR's wire contract; only the base URL is configurable.
    static final String GUEST_VALIDATE = "/api/v1/internal/qr/guest-sessions/validate";
    /** The detail QR puts in every guest refusal, so that it can be told apart from a missing route. */
    static final String UNAVAILABLE_GUEST = "Guest session is unavailable.";
    /** The one fixed code QR (PR #44) puts in every guest refusal; it does not say why the guest is unavailable. */
    static final String GUEST_SESSION_UNAVAILABLE = "GUEST_SESSION_UNAVAILABLE";

    @SuppressWarnings("java:S1075") // The path belongs to QR's wire contract; only the base URL is configurable.
    private static final String TABLES = "/api/v1/internal/tables";
    private static final byte[] NO_BODY = new byte[0];
    /** The header QR reads the delegated staff token from on the close and clean routes (QR PR #51). */
    static final String STAFF_TOKEN_HEADER = "X-Staff-Token";

    private final RestClient client;
    private final JsonMapper requests;
    private final WaiterQrRequestSigner signer;

    public QrClient(RestClient.Builder builder, ServiceUrlProperties urls, JsonMapper json,
                    WaiterQrRequestSigner signer) {
        this.requests = json;
        this.signer = signer;
        JsonMapper answers = json.rebuild().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        this.client = builder.clone().baseUrl(urls.qrUrl().toString())
                .configureMessageConverters(converters -> converters.withJsonConverter(
                        new JacksonJsonHttpMessageConverter(answers)))
                .build();
    }

    /** Every table of the restaurant, read page by page with a fixed upper bound of requests. */
    public List<QrTableDto> listAllTables(UUID restaurantId) {
        List<QrTableDto> tables = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        int page = 0;
        while (true) {
            int requested = page;
            String target = TABLES + "?" + RESTAURANT_ID + "=" + restaurantId + "&page=" + requested + "&size=" + PAGE_SIZE;
            QrTablePageDto result = DownstreamCalls.execute(DEPENDENCY, () -> client.get().uri(target)
                    .headers(headers -> sign(headers, WaiterQrRequestSigner.TABLE_READ, HttpMethod.GET, target, NO_BODY, false))
                    .retrieve().body(QrTablePageDto.class));
            requireConsistentPage(result, requested);
            for (QrTableDto table : result.items()) {
                requireTenant(restaurantId, table);
                if (!seen.add(table.id())) throw badResponse("duplicate table in the listing");
                tables.add(table);
            }
            if (tables.size() == result.totalElements()) return List.copyOf(tables);
            // Another page is needed, so this one must have made progress and must not overshoot the total.
            if (tables.size() > result.totalElements() || result.items().isEmpty()) {
                throw badResponse("table count does not match the total");
            }
            page++;
        }
    }

    private static void requireConsistentPage(QrTablePageDto result, int requested) {
        if (result.page() != requested || result.size() != PAGE_SIZE) {
            throw badResponse("page metadata does not match the request");
        }
        if (result.totalElements() > MAX_TABLES) {
            throw new SnapshotLimitExceededException(
                    "The restaurant has more than " + MAX_TABLES + " tables; the table snapshot is not supported");
        }
        if (result.items().size() > PAGE_SIZE) throw badResponse("page is larger than requested");
    }

    public Optional<QrTableDto> findTable(UUID restaurantId, UUID tableId) {
        String target = TABLES + "/" + tableId + "?" + RESTAURANT_ID + "=" + restaurantId;
        Optional<QrTableDto> table = DownstreamCalls.executeOptional(DEPENDENCY, () -> client.get().uri(target)
                .headers(headers -> sign(headers, WaiterQrRequestSigner.TABLE_READ, HttpMethod.GET, target, NO_BODY, false))
                .retrieve().body(QrTableDto.class));
        table.ifPresent(found -> {
            requireTenant(restaurantId, found);
            if (!found.id().equals(tableId)) throw badResponse("table id does not match the request");
        });
        return table;
    }

    /**
     * Asks QR which restaurant, table, table session and guest session an active guest credential belongs to, over
     * the shared QR endpoint that Order uses as well. An empty result means QR's documented guest refusal: a 404
     * problem saying the guest session is unavailable (unknown, ended, expired, or its table session is no longer
     * open; QR does not say which). Any other 404, e.g. a missing route, is no usable answer rather than an unknown
     * guest. The token is sent in the body only and is never logged; a 401/403 from QR is a rejection of Waiter
     * itself, not of the guest.
     */
    public Optional<QrGuestContextDto> verifyGuest(String guestToken) {
        // The credential is bound to the digest of the exact bytes sent, so the body is serialized once, here.
        byte[] body = requests.writeValueAsBytes(new QrGuestVerifyRequest(guestToken));
        try {
            QrGuestContextDto context = client.post()
                    .uri(GUEST_VALIDATE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> sign(headers, WaiterQrRequestSigner.GUEST_VALIDATE, HttpMethod.POST,
                            GUEST_VALIDATE, body, false))
                    .body(raw(body))
                    .retrieve().body(QrGuestContextDto.class);
            if (context == null) throw badResponse("empty answer to the guest validation");
            return Optional.of(context);
        } catch (RestClientResponseException refusal) {
            if (isUnavailableGuest(refusal)) return Optional.empty();
            throw DownstreamCalls.translate(DEPENDENCY, refusal);
        } catch (RestClientException failure) {
            throw DownstreamCalls.translate(DEPENDENCY, failure);
        }
    }

    private static String sessionPath(UUID tableId, UUID tableSessionId) {
        return TABLES + "/" + tableId + "/sessions/" + tableSessionId;
    }

    /**
     * Signs the request for QR: one service credential per request, bound to its method, target (with the query
     * string, exactly as sent) and body digest. The close and clean routes also need the staff member's token, taken
     * from the request being served. Outside a request (the reconciler) there is none and nothing is invented, so QR
     * answers 401, which is reported as a rejection of Waiter and never as "closed".
     */
    private void sign(HttpHeaders headers, String scope, HttpMethod method, String target, byte[] body,
                      boolean delegated) {
        String staffToken = delegated ? StaffAccessToken.current().orElse(null) : null;
        signer.authorization(scope, method, target, body, staffToken).ifPresent(credential -> {
            headers.set(HttpHeaders.AUTHORIZATION, credential);
            if (staffToken != null) headers.set(STAFF_TOKEN_HEADER, staffToken);
        });
    }

    /** The exact bytes the credential is bound to; a plain byte[] body would be re-encoded by the JSON converter. */
    private static StreamingHttpOutputMessage.Body raw(byte[] body) {
        return out -> out.write(body);
    }

    /**
     * QR's guest refusal: a 404 problem. QR marks it with the fixed {@code code} (PR #44). While the transition lasts,
     * the older fixed {@code detail} of its InvalidGuestSessionException is accepted as well; drop that comparison
     * once both environments send the code.
     */
    private static boolean isUnavailableGuest(RestClientResponseException refusal) {
        if (refusal.getStatusCode().value() != HttpStatus.NOT_FOUND.value()) return false;
        HttpHeaders headers = refusal.getResponseHeaders();
        MediaType type = headers == null ? null : headers.getContentType();
        if (type == null || !MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(type)) return false;
        try {
            JsonNode problem = REJECTION_JSON.readTree(refusal.getResponseBodyAsString());
            return problem.path("status").asInt(0) == HttpStatus.NOT_FOUND.value()
                    && (GUEST_SESSION_UNAVAILABLE.equals(problem.path("code").asString(""))
                    || UNAVAILABLE_GUEST.equals(problem.path("detail").asString("")));
        } catch (RuntimeException _) {
            return false;
        }
    }

    // ----- table commands (close: QR K11; cleaning: DRAFT) -----

    /** Refusals QR may give to a close; anything else becomes {@link QrRejectedException#GENERIC}. */
    static final Set<String> KNOWN_CLOSE_REJECTIONS = Set.of("SESSION_NOT_FOUND", "SESSION_TABLE_MISMATCH",
            "SESSION_VERSION_CONFLICT", "SESSION_NOT_OPEN", "SESSION_ALREADY_CLOSED", "CLOSURE_BLOCKED", "COMMAND_CONFLICT");
    /** Refusals QR may give to a cleaning command. */
    static final Set<String> KNOWN_CLEAN_REJECTIONS = Set.of("TABLE_NOT_FOUND", "TABLE_VERSION_CONFLICT",
            "TABLE_NOT_CLEANING", "TABLE_SESSION_ACTIVE", "COMMAND_CONFLICT");
    private static final JsonMapper REJECTION_JSON = JsonMapper.builder().build();

    /**
     * Sends the close command. QR alone decides whether the session may be closed: it orders the close with
     * Order's checkout, lets Order verify every order and the payment facts, and ends the main session and every
     * guest session in one local transaction. Three kinds of outcome:
     * <ul>
     *   <li>an answer: {@code 200 CLOSED} (all sessions ended, possibly with the K13 reconciliation warning) or
     *       {@code 202 CLOSING} (accepted, not finished);</li>
     *   <li>{@link QrRejectedException}: QR definitively did not close the session ({@code 409 CLOSURE_BLOCKED},
     *       a stale version, another session ...);</li>
     *   <li>{@link DependencyException}: no usable answer. The close may or may not have happened and nothing
     *       here sends it again: resending is a decision of the caller, with the same command id.</li>
     * </ul>
     * A 401/403 is reported as a dependency rejection ({@code REJECTED}): QR did not process this request.
     */
    public QrCloseResultDto closeSession(QrCloseRequestDto command) {
        String target = sessionPath(command.tableId(), command.tableSessionId()) + "/close";
        byte[] payload = requests.writeValueAsBytes(command);
        try {
            ResponseEntity<QrCloseResultDto> answer = client.post().uri(target)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", command.commandId().toString())
                    .headers(headers -> sign(headers, WaiterQrRequestSigner.SESSION_CLOSE, HttpMethod.POST, target, payload, true))
                    .body(raw(payload))
                    .retrieve().toEntity(QrCloseResultDto.class);
            QrCloseResultDto body = answer.getBody();
            if (body == null) throw badResponse("empty answer to the close command");
            int status = answer.getStatusCode().value();
            boolean consistent = (status == HttpStatus.OK.value() && body.state() == QrCloseResultDto.State.CLOSED)
                    || (status == HttpStatus.ACCEPTED.value() && body.state() == QrCloseResultDto.State.CLOSING);
            if (!consistent) throw badResponse("status " + status + " does not match the state " + body.state());
            if (!body.commandId().equals(command.commandId())) throw badResponse("answer for another command");
            if (!body.tableId().equals(command.tableId()) || !body.tableSessionId().equals(command.tableSessionId())) {
                throw badResponse("answer for another table or session");
            }
            if (body.table() != null) requireTenant(command.restaurantId(), body.table());
            if (body.state() == QrCloseResultDto.State.CLOSED && (body.table() == null
                    || body.table().operationalStatus() != TableOperationalStatus.CLEANING)) {
                throw badResponse("a closed session must leave the table cleaning");
            }
            return body;
        } catch (RestClientResponseException refusal) {
            throw refused(refusal, "SESSION_NOT_FOUND", KNOWN_CLOSE_REJECTIONS);
        } catch (RestClientException failure) {
            throw DownstreamCalls.translate(DEPENDENCY, failure);
        }
    }

    /**
     * What QR recorded for a close command id; empty if QR has no record (not received, still being written, or not
     * this table and session). QR's status read never runs the command again. Its {@code table} is the current
     * table, which may already be cleaned or serve the next visit; that says nothing against the old closure.
     */
    public Optional<QrCloseResultDto> closeStatus(UUID restaurantId, UUID tableId, UUID tableSessionId, UUID commandId) {
        String target = sessionPath(tableId, tableSessionId) + "/close-commands/" + commandId + "?" + RESTAURANT_ID + "=" + restaurantId;
        Optional<QrCloseResultDto> status = DownstreamCalls.executeOptional(DEPENDENCY, () -> client.get().uri(target)
                .headers(headers -> sign(headers, WaiterQrRequestSigner.SESSION_CLOSE, HttpMethod.GET, target, NO_BODY, true))
                .retrieve().body(QrCloseResultDto.class));
        status.ifPresent(found -> {
            if (!found.commandId().equals(commandId)) throw badResponse("status of another command");
            if (!found.tableId().equals(tableId) || !found.tableSessionId().equals(tableSessionId)) {
                throw badResponse("status of another table or session");
            }
            if (found.table() != null) requireTenant(restaurantId, found.table());
        });
        return status;
    }

    /**
     * Sends the cleaning command. QR alone decides whether the table may become available: it must be
     * {@code CLEANING}, the version must be current and no open or closing main session may remain. The same
     * three kinds of outcome apply as for {@link #closeSession}.
     */
    public QrCleanResultDto markCleaned(QrCleanRequestDto command) {
        String target = TABLES + "/" + command.tableId() + "/cleaned";
        byte[] payload = requests.writeValueAsBytes(command);
        try {
            QrCleanResultDto answer = client.post().uri(target)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", command.commandId().toString())
                    .headers(headers -> sign(headers, WaiterQrRequestSigner.TABLE_CLEAN, HttpMethod.POST, target, payload, true))
                    .body(raw(payload))
                    .retrieve().body(QrCleanResultDto.class);
            if (answer == null) throw badResponse("empty answer to the cleaning command");
            if (!answer.commandId().equals(command.commandId())) throw badResponse("answer for another command");
            if (answer.status() == QrCleanResultDto.Status.REJECTED) throw badResponse("a rejection must not be a 200");
            requireCleanTarget(command.restaurantId(), command.tableId(), answer.table());
            if (!answer.tableReady()) throw badResponse("an applied cleaning must leave the table available");
            return answer;
        } catch (RestClientResponseException refusal) {
            throw refused(refusal, "TABLE_NOT_FOUND", KNOWN_CLEAN_REJECTIONS);
        } catch (RestClientException failure) {
            throw DownstreamCalls.translate(DEPENDENCY, failure);
        }
    }

    /** What QR recorded for a cleaning command id; empty if QR has no record of it. */
    public Optional<QrCleanResultDto> cleanStatus(UUID restaurantId, UUID tableId, UUID commandId) {
        String target = TABLES + "/" + tableId + "/clean-commands/" + commandId + "?" + RESTAURANT_ID + "=" + restaurantId;
        Optional<QrCleanResultDto> status = DownstreamCalls.executeOptional(DEPENDENCY, () -> client.get().uri(target)
                .headers(headers -> sign(headers, WaiterQrRequestSigner.TABLE_CLEAN, HttpMethod.GET, target, NO_BODY, true))
                .retrieve().body(QrCleanResultDto.class));
        status.ifPresent(found -> {
            if (!found.commandId().equals(commandId)) throw badResponse("status of another command");
            if (found.table() != null) requireCleanTarget(restaurantId, tableId, found.table());
        });
        return status;
    }

    private static void requireCleanTarget(UUID restaurantId, UUID tableId, QrTableDto table) {
        requireTenant(restaurantId, table);
        if (!table.id().equals(tableId)) throw badResponse("answer for another table");
    }

    /** A definite refusal (400/404/409/422) is a rejection; 401/403 and everything else is no usable answer. */
    private static RuntimeException refused(RestClientResponseException refusal, String notFoundCode, Set<String> known) {
        int status = refusal.getStatusCode().value();
        if (!DownstreamCalls.isDefiniteRefusal(status)) {
            return DownstreamCalls.translate(DEPENDENCY, refusal);
        }
        if (status == HttpStatus.NOT_FOUND.value()) {
            return new QrRejectedException(status, notFoundCode, List.of());
        }
        try {
            JsonNode body = REJECTION_JSON.readTree(refusal.getResponseBodyAsString());
            String code = body.path("code").asString("");
            List<String> blockers = new ArrayList<>();
            for (JsonNode blocker : body.path("blockers")) blockers.add(blocker.asString(""));
            return new QrRejectedException(status, known.contains(code) ? code : QrRejectedException.GENERIC,
                    knownBlockers(blockers));
        } catch (RuntimeException _) {
            return new QrRejectedException(status, QrRejectedException.GENERIC, List.of());
        }
    }

    /** A reason QR may give for a close; used when the status answer carries a rejection code. */
    public static String knownCloseRejection(String code) {
        return code != null && KNOWN_CLOSE_REJECTIONS.contains(code) ? code : QrRejectedException.GENERIC;
    }

    /** A reason QR may give for a cleaning command; used when the status answer carries a rejection code. */
    public static String knownCleanRejection(String code) {
        return code != null && KNOWN_CLEAN_REJECTIONS.contains(code) ? code : QrRejectedException.GENERIC;
    }

    /** Only blocker codes of the fixed set are kept; foreign text never reaches the panel. */
    public static List<String> knownBlockers(List<String> codes) {
        return codes.stream().map(ClosureBlocker::parse).flatMap(Optional::stream).map(Enum::name).distinct().toList();
    }

    private static void requireTenant(UUID restaurantId, QrTableDto table) {
        if (!restaurantId.equals(table.restaurantId())) throw badResponse("table belongs to another restaurant");
    }

    private static DependencyException badResponse(String detail) {
        return new DependencyException(DEPENDENCY, DependencyFailure.BAD_RESPONSE,
                "qr service returned an unusable response: " + detail);
    }
}

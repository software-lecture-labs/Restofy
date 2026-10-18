package com.restofy.waiter.infrastructure.client.qr;

import com.restofy.waiter.domain.enums.ClosureBlocker;
import com.restofy.waiter.domain.enums.TableOperationalStatus;
import com.restofy.waiter.domain.exception.DependencyException;
import com.restofy.waiter.domain.exception.DependencyFailure;
import com.restofy.waiter.domain.exception.SnapshotLimitExceededException;
import com.restofy.waiter.infrastructure.client.DownstreamCalls;
import com.restofy.waiter.infrastructure.config.ServiceUrlProperties;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
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
 * against the caller's restaurant, so data of another tenant is never passed on.
 * The contract is a DRAFT (docs/waiter/integration-contracts.md); QR has not implemented it yet. Besides the
 * reads it carries the two table commands, closing a main session and marking a table cleaned: QR alone
 * decides them, and nothing here sends a command again on its own.
 */
@Component
public class QrClient {

    public static final String DEPENDENCY = "qr";
    /** Largest restaurant the table snapshot supports; bigger restaurants fail instead of being cut short. */
    public static final int MAX_TABLES = 500;
    static final int PAGE_SIZE = 200;

    private final RestClient client;

    public QrClient(RestClient.Builder builder, ServiceUrlProperties urls) {
        this.client = builder.clone().baseUrl(urls.qrUrl().toString()).build();
    }

    /** Every table of the restaurant, read page by page with a fixed upper bound of requests. */
    public List<QrTableDto> listAllTables(UUID restaurantId) {
        List<QrTableDto> tables = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        int page = 0;
        while (true) {
            int requested = page;
            QrTablePageDto result = DownstreamCalls.execute(DEPENDENCY, () -> client.get()
                    .uri(uri -> uri.path("/internal/v1/tables")
                            .queryParam("restaurantId", restaurantId)
                            .queryParam("page", requested)
                            .queryParam("size", PAGE_SIZE)
                            .build())
                    .retrieve().body(QrTablePageDto.class));
            if (result.page() != requested || result.size() != PAGE_SIZE) {
                throw badResponse("page metadata does not match the request");
            }
            if (result.totalElements() > MAX_TABLES) {
                throw new SnapshotLimitExceededException(
                        "The restaurant has more than " + MAX_TABLES + " tables; the table snapshot is not supported");
            }
            if (result.items().size() > PAGE_SIZE) throw badResponse("page is larger than requested");
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

    public Optional<QrTableDto> findTable(UUID restaurantId, UUID tableId) {
        Optional<QrTableDto> table = DownstreamCalls.executeOptional(DEPENDENCY, () -> client.get()
                .uri(uri -> uri.path("/internal/v1/tables/{tableId}")
                        .queryParam("restaurantId", restaurantId).build(tableId))
                .retrieve().body(QrTableDto.class));
        table.ifPresent(found -> {
            requireTenant(restaurantId, found);
            if (!found.id().equals(tableId)) throw badResponse("table id does not match the request");
        });
        return table;
    }

    /**
     * Asks QR which restaurant, table, table session and guest session a guest credential belongs to. An empty
     * result means QR does not know the credential (unknown, expired or revoked). The token is sent in the
     * body only and is never logged; a 401/403 from QR is a rejection of Waiter itself, not of the guest.
     */
    public Optional<QrGuestContextDto> verifyGuest(String guestToken) {
        return DownstreamCalls.executeOptional(DEPENDENCY, () -> client.post()
                .uri("/internal/v1/guest-sessions/verify")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(new QrGuestVerifyRequest(guestToken))
                .retrieve().body(QrGuestContextDto.class));
    }

    // ----- table commands (WAITER-04, DRAFT) -----

    /** Refusals QR may give to a close; anything else becomes {@link QrRejectedException#GENERIC}. */
    static final Set<String> KNOWN_CLOSE_REJECTIONS = Set.of("SESSION_NOT_FOUND", "SESSION_TABLE_MISMATCH",
            "SESSION_VERSION_CONFLICT", "SESSION_NOT_OPEN", "SESSION_ALREADY_CLOSED", "CLOSURE_BLOCKED", "COMMAND_CONFLICT");
    /** Refusals QR may give to a cleaning command. */
    static final Set<String> KNOWN_CLEAN_REJECTIONS = Set.of("TABLE_NOT_FOUND", "TABLE_VERSION_CONFLICT",
            "TABLE_NOT_CLEANING", "TABLE_SESSION_ACTIVE", "COMMAND_CONFLICT");
    private static final JsonMapper REJECTION_JSON = JsonMapper.builder().build();

    /**
     * Sends the close command. QR alone decides, atomically, whether the session may be closed: it stops new
     * order acceptance, settles the orders in flight, verifies delivery, balance and payment certainty with
     * their owners, and ends the main session and every guest session. Three kinds of outcome:
     * <ul>
     *   <li>an answer: {@code 200 CLOSED} (all sessions ended) or {@code 202 CLOSING} (accepted, not finished);</li>
     *   <li>{@link QrRejectedException}: QR definitively did not close the session;</li>
     *   <li>{@link DependencyException}: no usable answer. The close may or may not have happened and nothing
     *       here sends it again: resending is a decision of the caller, with the same command id.</li>
     * </ul>
     * A 401/403 is reported as a dependency rejection ({@code REJECTED}): QR did not process the request.
     */
    public QrCloseResultDto closeSession(QrCloseRequestDto command) {
        try {
            ResponseEntity<QrCloseResultDto> answer = client.post()
                    .uri("/internal/v1/tables/{tableId}/sessions/{tableSessionId}/close", command.tableId(),
                            command.tableSessionId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", command.commandId().toString())
                    .body(command)
                    .retrieve().toEntity(QrCloseResultDto.class);
            QrCloseResultDto body = answer.getBody();
            if (body == null) throw badResponse("empty answer to the close command");
            int status = answer.getStatusCode().value();
            boolean consistent = (status == 200 && body.state() == QrCloseResultDto.State.CLOSED)
                    || (status == 202 && body.state() == QrCloseResultDto.State.CLOSING);
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

    /** What QR recorded for a close command id; empty if QR has no record (not received, or still being written). */
    public Optional<QrCloseResultDto> closeStatus(UUID restaurantId, UUID tableId, UUID tableSessionId, UUID commandId) {
        Optional<QrCloseResultDto> status = DownstreamCalls.executeOptional(DEPENDENCY, () -> client.get()
                .uri(uri -> uri.path("/internal/v1/tables/{tableId}/sessions/{tableSessionId}/close-commands/{commandId}")
                        .queryParam("restaurantId", restaurantId).build(tableId, tableSessionId, commandId))
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
        try {
            QrCleanResultDto answer = client.post()
                    .uri("/internal/v1/tables/{tableId}/cleaned", command.tableId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", command.commandId().toString())
                    .body(command)
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
        Optional<QrCleanResultDto> status = DownstreamCalls.executeOptional(DEPENDENCY, () -> client.get()
                .uri(uri -> uri.path("/internal/v1/tables/{tableId}/clean-commands/{commandId}")
                        .queryParam("restaurantId", restaurantId).build(tableId, commandId))
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
        if (status != 404 && status != 400 && status != 409 && status != 422) {
            return DownstreamCalls.translate(DEPENDENCY, refusal);
        }
        if (status == 404) return new QrRejectedException(404, notFoundCode, List.of());
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

package com.restofy.waiter.infrastructure.client.order;

import com.restofy.waiter.domain.enums.OrderView;
import com.restofy.waiter.domain.exception.DependencyException;
import com.restofy.waiter.domain.exception.DependencyFailure;
import com.restofy.waiter.infrastructure.client.DownstreamCalls;
import com.restofy.waiter.infrastructure.config.ServiceUrlProperties;
import com.restofy.waiter.infrastructure.security.StaffAccessToken;
import com.restofy.waiter.infrastructure.security.StaffSessionVerifier;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.DefaultUriBuilderFactory;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Adapter for the Order service (its real contract, PR #45 / docs/order-service/entegrasyon-rehberi.md): the
 * owner of orders, snapshots, cancellations and the served fact. Queries are read-only (the search POSTs only
 * carry large filters in a body) and are made as the staff member of the current request, with that member's
 * verified session. Order derives the restaurant from that session; the signed POST reads bind the tenant
 * in the service JWT, and the detail and cancellation GETs carry the staff cookie.
 * The single command is {@link #serve}: it is signed as Waiter's own service identity and delegates the staff
 * member through {@code X-Restofy-Staff-Delegation}. Order's answers are mapped to Waiter's {@link OrderDto}
 * by {@link OrderMapping}.
 */
@Component
public class OrderClient {

    public static final String DEPENDENCY = "order";
    /** Upper bound of session ids in one request; equals the table snapshot limit of the QR adapter. */
    public static final int MAX_SESSION_IDS = 500;
    public static final String STAFF_DELEGATION_HEADER = "X-Restofy-Staff-Delegation";

    private static final String ORDERS = "/api/v1/orders";
    private static final String INTERNAL_ORDERS = "/api/v1/internal/orders";

    private final RestClient client;
    private final JsonMapper requests;
    private final WaiterOrderRequestSigner signer;
    private final DefaultUriBuilderFactory uris;
    private final boolean secureStaffTransport;

    public OrderClient(RestClient.Builder builder, ServiceUrlProperties urls, JsonMapper json,
                       WaiterOrderRequestSigner signer, Environment environment) {
        URI orderUrl = urls.orderUrl();
        this.uris = new DefaultUriBuilderFactory(orderUrl.toString());
        this.client = builder.clone().uriBuilderFactory(uris).build();
        this.requests = json;
        this.signer = signer;
        // HTTP is suitable only for local test peers bound to a literal loopback address.
        this.secureStaffTransport = "https".equalsIgnoreCase(orderUrl.getScheme())
                || (environment.matchesProfiles("test")
                && ("127.0.0.1".equals(orderUrl.getHost()) || "[::1]".equals(orderUrl.getHost())));
    }

    /** Active order count per live session in one request; sessions without active orders map to zero. */
    public Map<UUID, Integer> activeOrderCounts(UUID restaurantId, Collection<UUID> tableSessionIds) {
        if (tableSessionIds.isEmpty()) return Map.of();
        if (tableSessionIds.size() > MAX_SESSION_IDS) {
            throw new IllegalArgumentException("Too many session ids for one request");
        }
        Set<UUID> requested = new HashSet<>(tableSessionIds);
        OrderWire.SessionCounts result = readPost(restaurantId, ORDERS + "/session-summaries",
                new SessionSummaryRequest(List.copyOf(requested)), OrderWire.SessionCounts.class);
        Map<UUID, Integer> counts = new HashMap<>();
        for (OrderWire.SessionCount entry : result.items()) {
            if (!requested.contains(entry.tableSessionId())) throw badResponse("summary for a session that was not requested");
            if (entry.activeOrderCount() > Integer.MAX_VALUE) throw badResponse("summary count out of range");
            if (counts.put(entry.tableSessionId(), entry.activeOrderCount().intValue()) != null) {
                throw badResponse("duplicate session summary");
            }
        }
        return counts;
    }

    /**
     * One page of orders (rows without items). Filters and ordering are applied by Order to the
     * whole data set; the answer is checked against the request so a downstream that ignores a filter is never
     * shown.
     */
    public OrderPageDto search(UUID restaurantId, UUID tableId, Collection<UUID> tableSessionIds, OrderView view,
                               int page, int size) {
        if (tableSessionIds != null && (tableSessionIds.isEmpty() || tableSessionIds.size() > MAX_SESSION_IDS)) {
            throw new IllegalArgumentException("tableSessionIds must hold between 1 and " + MAX_SESSION_IDS + " ids");
        }
        List<UUID> sessions = tableSessionIds == null ? null : List.copyOf(new HashSet<>(tableSessionIds));
        OrderWire.Page result = readPost(restaurantId, ORDERS + "/search",
                OrderSearchRequest.of(tableId, sessions, view, page, size), OrderWire.Page.class);
        if (result.page() != page || result.limit() != size || result.items().size() > size
                || result.totalItems() < result.items().size()) {
            throw badResponse("page metadata does not match the request");
        }
        Set<UUID> ids = new HashSet<>();
        List<OrderDto> orders;
        try {
            orders = result.items().stream().map(row -> OrderMapping.fromSummary(restaurantId, row)).toList();
        } catch (IllegalArgumentException | NullPointerException _) {
            throw badResponse("order with an unknown or incomplete state");
        }
        for (OrderDto order : orders) {
            if (!ids.add(order.id())) throw badResponse("duplicate order in the page");
            requireMatchesFilters(order, tableId, sessions, view);
        }
        return new OrderPageDto(orders, result.page(), result.limit(), result.totalItems());
    }

    private static void requireMatchesFilters(OrderDto order, UUID tableId, List<UUID> sessions, OrderView view) {
        if (tableId != null && !tableId.equals(order.tableId())) throw badResponse("order of another table");
        if (sessions != null && !sessions.contains(order.tableSessionId())) {
            throw badResponse("order of a session that was not requested");
        }
        if (view != null && !view.matches(order.businessStatus(), order.preparation().status(), order.deliveryStatus())) {
            throw badResponse("order does not match the requested view");
        }
    }

    /** One order with its items, version and cancellations (two reads: the detail and the cancellation list). */
    public Optional<OrderDto> findOrder(UUID restaurantId, UUID orderId) {
        Optional<OrderWire.Detail> detail = DownstreamCalls.executeOptional(DEPENDENCY, () -> client.get()
                .uri(ORDERS + "/" + orderId)
                .headers(this::asStaff)
                .retrieve().body(OrderWire.Detail.class));
        if (detail.isEmpty()) return Optional.empty();
        if (!detail.get().orderId().equals(orderId)) throw badResponse("order id does not match the request");
        Optional<OrderWire.Cancellations> cancellations = DownstreamCalls.executeOptional(DEPENDENCY, () -> client.get()
                .uri(ORDERS + "/" + orderId + "/cancellations")
                .headers(this::asStaff)
                .retrieve().body(OrderWire.Cancellations.class));
        if (cancellations.isEmpty()) return Optional.empty();
        if (!cancellations.get().orderId().equals(orderId)) throw badResponse("cancellations of another order");
        for (OrderWire.Cancellation cancellation : cancellations.get().cancellations()) {
            if (!cancellation.orderId().equals(orderId)) throw badResponse("cancellation of another order");
        }
        try {
            return Optional.of(OrderMapping.fromDetail(restaurantId, detail.get(), cancellations.get().cancellations()));
        } catch (IllegalArgumentException | NullPointerException _) {
            throw badResponse("order with an unknown or incomplete state");
        }
    }

    /**
     * Sends the serve command, signed as Waiter and delegated by the staff member of the current request. Order
     * alone decides, atomically, whether the order may be served (tenant, ownership, session, version, ready,
     * not cancelled, not yet served). Three kinds of outcome:
     * <ul>
     *   <li>a returned receipt: Order applied the command (or had applied the same command id before);</li>
     *   <li>{@link OrderRejectedException}: Order definitively did not apply it;</li>
     *   <li>{@link DependencyException}: no usable answer. The command may or may not have been applied, and
     *       nothing here sends it again: resending is a decision of the caller, with the same command id.</li>
     * </ul>
     * A 401/403 is reported as a dependency rejection ({@code REJECTED}): Order did not process the request.
     */
    public ServeResponseDto serve(UUID orderId, ServeCommandRequestDto command) {
        URI uri = uris.expand(INTERNAL_ORDERS + "/" + orderId + "/serve");
        byte[] body = requests.writeValueAsBytes(command);
        try {
            ServeResponseDto answer = client.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", command.commandId().toString())
                    .headers(headers -> delegated(headers, WaiterOrderRequestSigner.SERVE,
                            command.restaurantId(), HttpMethod.POST, uri, body))
                    .body(raw(body))
                    .retrieve().body(ServeResponseDto.class);
            if (answer == null) throw badResponse("empty answer to the serve command");
            if (!answer.commandId().equals(command.commandId())) throw badResponse("answer for another command");
            if (!answer.orderId().equals(orderId)) throw badResponse("answer for another order");
            if (!answer.tableSessionId().equals(command.tableSessionId())) throw badResponse("answer for another session");
            return answer;
        } catch (RestClientResponseException refusal) {
            int status = refusal.getStatusCode().value();
            if (DownstreamCalls.isDefiniteRefusal(status)) {
                throw new OrderRejectedException(rejectionCode(status, refusal));
            }
            throw DownstreamCalls.translate(DEPENDENCY, refusal);
        } catch (RestClientException failure) {
            throw DownstreamCalls.translate(DEPENDENCY, failure);
        }
    }

    /**
     * What Order recorded for a command id; empty if Order has no record of it. A 404 is "unknown", never proof
     * that the command was not applied. 401, 403 and 503 stay dependency errors and are never read as a
     * rejection of the command.
     */
    public Optional<ServeStatusDto> serveStatus(UUID restaurantId, UUID orderId, UUID commandId) {
        String path = INTERNAL_ORDERS + "/" + orderId + "/serve-commands/" + commandId + "?restaurantId=" + restaurantId;
        URI uri = uris.expand(path);
        Optional<ServeStatusDto> status = DownstreamCalls.executeOptional(DEPENDENCY, () -> client.get()
                .uri(uri)
                .headers(headers -> delegated(headers, WaiterOrderRequestSigner.SERVE,
                        restaurantId, HttpMethod.GET, uri, new byte[0]))
                .retrieve().body(ServeStatusDto.class));
        status.ifPresent(found -> {
            if (!found.commandId().equals(commandId)) throw badResponse("status of another command");
            if (!found.orderId().equals(orderId)) throw badResponse("status of another order");
        });
        return status;
    }

    private <T> T readPost(UUID restaurantId, String path, Object request, Class<T> responseType) {
        byte[] body = requests.writeValueAsBytes(request);
        URI uri = uris.expand(path);
        return DownstreamCalls.execute(DEPENDENCY, () -> client.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> delegated(headers, WaiterOrderRequestSigner.READ,
                        restaurantId, HttpMethod.POST, uri, body))
                .body(raw(body))
                .retrieve().body(responseType));
    }

    private static StreamingHttpOutputMessage.Body raw(byte[] body) {
        return out -> out.write(body);
    }

    /** The detail and cancellation GETs use the staff member's verified access cookie. */
    private void asStaff(HttpHeaders headers) {
        StaffAccessToken.current().ifPresent(credential -> {
            requireSecureStaffTransport();
            headers.set(HttpHeaders.COOKIE, StaffSessionVerifier.ACCESS_COOKIE + "=" + credential);
        });
    }

    /**
     * Signs and delegates one Order request. Without a staff credential or a configured key nothing is sent,
     * so Order answers 401 and the call is a dependency rejection: no identity is invented.
     */
    private void delegated(HttpHeaders headers, String scope, UUID restaurantId, HttpMethod method, URI uri,
                           byte[] body) {
        StaffAccessToken.current().ifPresent(credential -> {
            requireSecureStaffTransport();
            String query = uri.getRawQuery();
            String target = uri.getRawPath() + (query == null ? "" : "?" + query);
            signer.authorization(scope, restaurantId, method, target, body, credential).ifPresent(authorization -> {
                headers.set(HttpHeaders.AUTHORIZATION, authorization);
                headers.set(STAFF_DELEGATION_HEADER, credential);
            });
        });
    }

    private void requireSecureStaffTransport() {
        if (!secureStaffTransport) {
            throw new DependencyException(DEPENDENCY, DependencyFailure.UNAVAILABLE,
                    "Order's staff connection requires HTTPS");
        }
    }

    /** Reasons Order may give; anything else becomes the generic code so foreign text is never reflected. */
    private static final Set<String> KNOWN_REJECTIONS = Set.of("ORDER_VERSION_CONFLICT",
            "ORDER_SESSION_MISMATCH", "ORDER_NOT_READY", "ORDER_CANCELLED", "ORDER_ALREADY_SERVED",
            "ORDER_NOT_DELIVERABLE", "ORDER_COMMAND_CONFLICT");
    private static final JsonMapper REJECTION_JSON = JsonMapper.builder().build();

    private static String rejectionCode(int status, RestClientResponseException refusal) {
        if (status == HttpStatus.NOT_FOUND.value()) return OrderRejectedException.NOT_FOUND;
        try {
            return knownRejection(REJECTION_JSON.readTree(refusal.getResponseBodyAsString()).path("code").asString(""));
        } catch (RuntimeException _) {
            return OrderRejectedException.GENERIC;
        }
    }

    /** A reason Order may give is passed on; any other text becomes the generic code. */
    public static String knownRejection(String code) {
        if (OrderRejectedException.NOT_FOUND.equals(code)) return code;
        return code != null && KNOWN_REJECTIONS.contains(code) ? code : OrderRejectedException.GENERIC;
    }

    private static DependencyException badResponse(String detail) {
        return new DependencyException(DEPENDENCY, DependencyFailure.BAD_RESPONSE,
                "order service returned an unusable response: " + detail);
    }
}

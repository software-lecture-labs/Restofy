package com.restofy.waiter.support;

import com.restofy.waiter.support.DownstreamStub.Request;
import com.restofy.waiter.support.DownstreamStub.Response;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-memory QR and Order behind a {@link DownstreamStub}. It behaves like a compliant implementation of the
 * DRAFT contract: it filters by restaurant, applies filters and ordering to the whole data set, then pages.
 * Misbehaving downstreams are simulated per test with their own routes.
 */
public final class FakeBackends {

    public static final JsonMapper JSON = JsonMapper.builder().build();

    /** {@code version} is QR's version of the table, {@code sessionVersion} QR's version of its live session. */
    public record Table(UUID id, UUID restaurantId, String number, String status, UUID sessionId, long version,
                        long sessionVersion) {

        public Table(UUID id, UUID restaurantId, String number, String status, UUID sessionId) {
            this(id, restaurantId, number, status, sessionId, 7, 3);
        }

        public Table with(String newStatus, UUID newSessionId) {
            return new Table(id, restaurantId, number, newStatus, newSessionId, version + 1, sessionVersion);
        }
    }

    /**
     * Test order; {@code preparation} is null, WAITING, PREPARING or READY, {@code payment} NOT_PAID, PENDING, PAID
     * or UNKNOWN. The version is Order's optimistic-locking version.
     */
    public record Order(UUID id, UUID restaurantId, UUID tableId, UUID sessionId, String number, String business,
                        String delivery, String preparation, Instant acceptedAt, Instant readyAt, Instant servedAt,
                        long version, String payment) {

        public Order(UUID id, UUID restaurantId, UUID tableId, UUID sessionId, String number, String business,
                     String delivery, String preparation, Instant acceptedAt, Instant readyAt, Instant servedAt) {
            this(id, restaurantId, tableId, sessionId, number, business, delivery, preparation, acceptedAt, readyAt,
                    servedAt, 4, "NOT_PAID");
        }

        Order served(Instant at) {
            return new Order(id, restaurantId, tableId, sessionId, number, business, "SERVED", preparation, acceptedAt,
                    readyAt, at, version + 1, payment);
        }

        Order cancelled() {
            return new Order(id, restaurantId, tableId, sessionId, number, "CANCELLED", delivery, preparation, acceptedAt,
                    readyAt, servedAt, version + 1, payment);
        }

        public Order withPayment(String newPayment) {
            return new Order(id, restaurantId, tableId, sessionId, number, business, delivery, preparation, acceptedAt,
                    readyAt, servedAt, version, newPayment);
        }

        public Order withBusiness(String newBusiness) {
            return new Order(id, restaurantId, tableId, sessionId, number, newBusiness, delivery, preparation, acceptedAt,
                    readyAt, servedAt, version, payment);
        }
    }

    /** What the Order stub recorded for a serve command id: the request digest and Order's answer. */
    record ServeRecord(String digest, boolean applied, int rejectionStatus, String rejectionCode) {
    }

    /** What the Order stub does wrong on the next serve command, to simulate an unreliable network or service. */
    public enum ServeFault {
        /** Order applies the command but the answer arrives after Waiter's read timeout. */
        LOST_ANSWER,
        /** Order is slow and never applies the command within Waiter's read timeout. */
        HANG_BEFORE_APPLY,
        /** Order answers 503 without applying the command. */
        SERVER_ERROR,
        /** Order rejects Waiter itself (no service identity). */
        UNAUTHORIZED,
        /** Order applies the command and answers 200 with an unreadable body. */
        GARBAGE_ANSWER
    }

    /** D-02 is open: whether a partially cancelled order may be served is Order's decision, not Waiter's. */
    public enum PartialCancellation { ALLOW, REJECT }

    /**
     * A guest credential as QR would know it. The statuses are QR's internal states; QR's answer only says whether the
     * guest is usable (an active guest of an open table session) and refuses every other guest with the same 404.
     */
    public record Guest(String token, UUID restaurantId, UUID tableId, UUID tableSessionId, UUID guestSessionId,
                        String tableSessionStatus, String guestStatus) {

        public Guest withStatuses(String tableSession, String guestSession) {
            return new Guest(token, restaurantId, tableId, tableSessionId, guestSessionId, tableSession, guestSession);
        }
    }

    public final List<Table> tables = new CopyOnWriteArrayList<>();
    public final List<Order> orders = new CopyOnWriteArrayList<>();
    private final Map<UUID, ServeRecord> serveRecords = new HashMap<>();
    private final Deque<ServeFault> serveFaults = new ArrayDeque<>();
    private final Deque<ServeFault> statusFaults = new ArrayDeque<>();
    public volatile PartialCancellation partialCancellation = PartialCancellation.ALLOW;
    /** Known guest credentials by token; an unknown token is answered with 404. */
    public final Map<String, Guest> guests = new ConcurrentHashMap<>();

    /** QR's shared guest validation (QR-INT-02) and the 404 problem it gives for every refused guest. */
    public static final String GUEST_VALIDATE = "/api/v1/internal/qr/guest-sessions/validate";
    public static final String UNAVAILABLE_GUEST = "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404,"
            + "\"detail\":\"Guest session is unavailable.\"}";

    private static final Pattern TABLE_ID = Pattern.compile("/api/v1/internal/tables/([0-9a-f-]{36})");
    private static final Pattern ORDER_ID = Pattern.compile("/api/v1/orders/([0-9a-f-]{36})");
    private static final Pattern ORDER_CANCELLATIONS = Pattern.compile("/api/v1/orders/([0-9a-f-]{36})/cancellations");

    public FakeBackends installOn(DownstreamStub stub) {
        stub.route("GET", "/api/v1/internal/tables", this::listTables);
        stub.route("GET", TABLE_ID.pattern(), this::getTable);
        stub.route("POST", "/api/v1/orders/session-summaries", this::sessionSummaries);
        stub.route("POST", "/api/v1/orders/search", this::searchOrders);
        stub.route("GET", ORDER_ID.pattern(), this::getOrder);
        stub.route("GET", ORDER_CANCELLATIONS.pattern(), this::getCancellations);
        stub.route("POST", GUEST_VALIDATE, this::verifyGuest);
        stub.route("POST", "/api/v1/internal/orders/[0-9a-f-]{36}/serve", this::serveOrder);
        stub.route("GET", "/api/v1/internal/orders/[0-9a-f-]{36}/serve-commands/[0-9a-f-]{36}", this::serveStatus);
        return this;
    }

    // ----- QR -----

    private Response listTables(Request request) {
        Map<String, String> query = request.query();
        UUID restaurantId = UUID.fromString(query.get("restaurantId"));
        int page = Integer.parseInt(query.get("page"));
        int size = Integer.parseInt(query.get("size"));
        List<Table> mine = tables.stream().filter(t -> t.restaurantId().equals(restaurantId))
                .sorted(Comparator.comparing(Table::id)).toList();
        int from = Math.min(page * size, mine.size());
        int to = Math.min(from + size, mine.size());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", mine.subList(from, to).stream().map(FakeBackends::tableJson).toList());
        body.put("page", page);
        body.put("size", size);
        body.put("totalElements", mine.size());
        return Response.json(200, write(body));
    }

    private Response getTable(Request request) {
        Matcher match = TABLE_ID.matcher(request.path());
        match.matches();
        UUID id = UUID.fromString(match.group(1));
        UUID restaurantId = UUID.fromString(request.query().get("restaurantId"));
        return tables.stream().filter(t -> t.id().equals(id) && t.restaurantId().equals(restaurantId)).findFirst()
                .map(t -> Response.json(200, write(tableJson(t))))
                .orElse(Response.json(404, "{\"status\":404}"));
    }

    public synchronized Table table(UUID id) {
        return tables.stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }

    public synchronized void replace(Table updated) {
        for (int index = 0; index < tables.size(); index++) {
            if (tables.get(index).id().equals(updated.id())) tables.set(index, updated);
        }
    }

    public static Map<String, Object> tableJson(Table table) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", table.id());
        json.put("restaurantId", table.restaurantId());
        json.put("tableNumber", table.number());
        json.put("displayName", "Table " + table.number());
        json.put("capacity", 4);
        json.put("operationalStatus", table.status());
        json.put("active", !"DISABLED".equals(table.status()));
        json.put("version", table.version());
        if (table.sessionId() == null) {
            json.put("currentSession", null);
        } else {
            Map<String, Object> session = new LinkedHashMap<>();
            session.put("id", table.sessionId());
            session.put("status", "OPEN");
            session.put("openedAt", "2026-10-16T09:00:00Z");
            session.put("lastActivityAt", "2026-10-16T09:30:00Z");
            session.put("scanCount", 2);
            session.put("version", table.sessionVersion());
            json.put("currentSession", session);
        }
        return json;
    }

    // ----- Order: the serve command (DRAFT contract) -----

    public synchronized void failNextServe(ServeFault fault) {
        serveFaults.add(fault);
    }

    /** The next serve result query fails (SERVER_ERROR answers 503, UNAUTHORIZED answers 401). */
    public synchronized void failNextServeStatus(ServeFault fault) {
        statusFaults.add(fault);
    }

    /** The key pair of Waiter's Order service identity: Order holds the public key, Waiter the private one. */
    public static final java.security.KeyPair ORDER_CALLER_KEYS = generateKeys();
    public static final String ORDER_CALLER_KEY_ID = "waiter-test-key";

    private static java.security.KeyPair generateKeys() {
        try {
            java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public static String orderCallerPrivateKey() {
        return java.util.Base64.getEncoder().encodeToString(ORDER_CALLER_KEYS.getPrivate().getEncoded());
    }

    private static String sha256(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /**
     * What Order's Waiter-serve filter demands of the serve command and of its result query: a Service
     * credential of Waiter (RS256, issuer, subject, audience, scope, 30 s lifetime) bound to this very request
     * (method, target, body digest) and to the delegated staff credential, a staff session of the same restaurant,
     * and no browser Cookie or Origin. 401 means no verifiable identity, 403 a valid identity that does not cover
     * the request.
     */
    private static Response verifyDelegatedIdentity(Request request, UUID restaurantId, String scope) {
        String authorization = request.header("Authorization");
        String delegated = request.header("X-Restofy-Staff-Delegation");
        if (authorization == null || !authorization.startsWith("Service ") || delegated == null || delegated.isBlank()) {
            return Response.json(401, "{\"status\":401,\"code\":\"UNAUTHENTICATED\"}");
        }
        io.jsonwebtoken.Claims claims;
        try {
            claims = io.jsonwebtoken.Jwts.parser().verifyWith(ORDER_CALLER_KEYS.getPublic())
                    .requireIssuer("restofy-waiter").requireSubject("waiter-service").requireAudience("restofy-order")
                    .require("token_type", "service").require("scope", scope).build()
                    .parseSignedClaims(authorization.substring("Service ".length()).trim()).getPayload();
        } catch (io.jsonwebtoken.JwtException | IllegalArgumentException invalid) {
            return Response.json(401, "{\"status\":401,\"code\":\"UNAUTHENTICATED\"}");
        }
        String query = request.rawQuery();
        String target = request.path() + (query == null || query.isEmpty() ? "" : "?" + query);
        boolean lifetime = claims.getIssuedAt() != null && claims.getExpiration() != null
                && claims.getIssuedAt().equals(claims.getNotBefore())
                && !claims.getExpiration().toInstant().isAfter(claims.getIssuedAt().toInstant().plusSeconds(30));
        boolean bound = lifetime && request.method().equals(claims.get("method", String.class))
                && target.equals(claims.get("target", String.class))
                && sha256(request.body() == null ? "" : request.body()).equals(claims.get("body_sha256", String.class))
                && sha256(delegated).equals(claims.get("staff_sha256", String.class))
                && restaurantId.toString().equals(claims.get("restaurant_id", String.class))
                && restaurantId.equals(staffRestaurant(delegated));
        boolean browserHeaders = request.header("Cookie") != null || request.header("Origin") != null;
        if (!bound || browserHeaders) {
            return Response.json(403, "{\"status\":403,\"code\":\"ACCESS_DENIED\"}");
        }
        return null;
    }

    /** Order-side cancellation racing with a serve command; returns false if the order was already served. */
    public synchronized boolean cancel(UUID orderId) {
        for (int index = 0; index < orders.size(); index++) {
            Order order = orders.get(index);
            if (order.id().equals(orderId)) {
                if (order.delivery().equals("SERVED") || order.business().equals("CANCELLED")) return false;
                orders.set(index, order.cancelled());
                return true;
            }
        }
        return false;
    }

    public synchronized Order order(UUID orderId) {
        return orders.stream().filter(o -> o.id().equals(orderId)).findFirst().orElseThrow();
    }

    private static final Pattern SERVE_PATH = Pattern.compile("/api/v1/internal/orders/([0-9a-f-]{36})/serve");
    private static final Pattern SERVE_STATUS_PATH =
            Pattern.compile("/api/v1/internal/orders/([0-9a-f-]{36})/serve-commands/([0-9a-f-]{36})");

    /**
     * Decides atomically (the whole method is one critical section, as Order's database transaction must be):
     * ownership, session, version, state, and idempotency by command id. This models the behaviour the DRAFT
     * contract demands from Order; it does not prove that the real Order will provide it.
     */
    private synchronized Response serveOrder(Request request) {
        ServeFault fault = serveFaults.poll();
        if (fault == ServeFault.SERVER_ERROR) return Response.json(503, "{}");
        if (fault == ServeFault.UNAUTHORIZED) return Response.json(401, "{}");
        if (fault == ServeFault.HANG_BEFORE_APPLY) return Response.json(504, "{}").delayed(1500);
        Matcher match = SERVE_PATH.matcher(request.path());
        match.matches();
        UUID orderId = UUID.fromString(match.group(1));
        JsonNode body = read(request.body());
        UUID commandId = UUID.fromString(body.get("commandId").asString());
        if (!commandId.toString().equals(request.header("Idempotency-Key"))) return rejection(400, "ORDER_REJECTED");
        UUID restaurantId = UUID.fromString(body.get("restaurantId").asString());
        Response denied = verifyDelegatedIdentity(request, restaurantId, "order:serve");
        if (denied != null) return denied;
        UUID sessionId = UUID.fromString(body.get("tableSessionId").asString());
        long expectedVersion = body.get("expectedVersion").asLong();
        String digest = orderId + "|" + sessionId + "|" + expectedVersion + "|" + body.get("requestedByUserId").asString();

        ServeRecord known = serveRecords.get(commandId);
        if (known != null) {
            if (!known.digest().equals(digest)) return rejection(409, "ORDER_COMMAND_CONFLICT");
            if (!known.applied()) return rejection(known.rejectionStatus(), known.rejectionCode());
            return Response.json(200, write(serveReceipt(commandId, "DUPLICATE", order(orderId))));
        }
        int index = indexOf(orderId, restaurantId);
        if (index < 0) return rejection(404, "ORDER_NOT_FOUND");   // not recorded: nothing to replay
        Order order = orders.get(index);
        String refusal = refusalFor(order, sessionId, expectedVersion);
        if (refusal != null) {
            serveRecords.put(commandId, new ServeRecord(digest, false, 409, refusal));
            return rejection(409, refusal);
        }
        orders.set(index, order.served(Instant.parse("2026-10-16T10:30:00Z")));
        serveRecords.put(commandId, new ServeRecord(digest, true, 0, null));
        Response answer = Response.json(200, fault == ServeFault.GARBAGE_ANSWER ? "not json"
                : write(serveReceipt(commandId, "APPLIED", orders.get(index))));
        return answer.delayed(fault == ServeFault.LOST_ANSWER ? 1500 : 0);
    }

    private String refusalFor(Order order, UUID sessionId, long expectedVersion) {
        if (!order.sessionId().equals(sessionId)) return "ORDER_SESSION_MISMATCH";
        if (order.version() != expectedVersion) return "ORDER_VERSION_CONFLICT";
        if (order.delivery().equals("SERVED")) return "ORDER_ALREADY_SERVED";
        if (order.business().equals("CANCELLED")) return "ORDER_CANCELLED";
        if (!"READY".equals(order.preparation())) return "ORDER_NOT_READY";
        if (order.business().equals("PARTIALLY_CANCELLED") && partialCancellation == PartialCancellation.REJECT) {
            return "ORDER_NOT_DELIVERABLE";
        }
        return null;   // A PAID order is not refused: prepayment neither blocks nor requires serving.
    }

    private int indexOf(UUID orderId, UUID restaurantId) {
        for (int index = 0; index < orders.size(); index++) {
            Order order = orders.get(index);
            if (order.id().equals(orderId) && order.restaurantId().equals(restaurantId)) return index;
        }
        return -1;
    }

    private static Map<String, Object> serveReceipt(UUID commandId, String outcome, Order order) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("commandId", commandId);
        answer.put("orderId", order.id());
        answer.put("tableSessionId", order.sessionId());
        answer.put("orderVersion", order.version());
        answer.put("servedAt", order.servedAt());
        answer.put("outcome", outcome);
        return answer;
    }

    private static Response rejection(int status, String code) {
        return Response.json(status, write(Map.of("status", status, "code", code)));
    }

    private synchronized Response serveStatus(Request request) {
        Matcher match = SERVE_STATUS_PATH.matcher(request.path());
        match.matches();
        UUID orderId = UUID.fromString(match.group(1));
        UUID commandId = UUID.fromString(match.group(2));
        Response denied = verifyDelegatedIdentity(request, UUID.fromString(request.query().get("restaurantId")), "order:serve");
        if (denied != null) return denied;
        ServeFault fault = statusFaults.poll();
        if (fault == ServeFault.SERVER_ERROR) return Response.json(503, "{}");
        if (fault == ServeFault.UNAUTHORIZED) return Response.json(401, "{}");
        ServeRecord known = serveRecords.get(commandId);
        if (known == null) return Response.json(404, "{\"status\":404}");
        Order order = order(orderId);
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("commandId", commandId);
        answer.put("orderId", orderId);
        answer.put("tableSessionId", order.sessionId());
        answer.put("orderVersion", known.applied() ? order.version() : null);
        answer.put("decidedAt", Instant.parse("2026-10-16T10:30:00Z"));
        answer.put("servedAt", known.applied() ? order.servedAt() : null);
        answer.put("status", known.applied() ? "APPLIED" : "REJECTED");
        answer.put("rejectionCode", known.rejectionCode());
        return Response.json(200, write(answer));
    }

    private Response verifyGuest(Request request) {
        Guest guest = guests.get(read(request.body()).get("guestToken").asString());
        if (guest == null || !"OPEN".equals(guest.tableSessionStatus()) || !"ACTIVE".equals(guest.guestStatus())) {
            return Response.problem(404, UNAVAILABLE_GUEST);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("restaurantId", guest.restaurantId());
        body.put("tableId", guest.tableId());
        body.put("tableSessionId", guest.tableSessionId());
        body.put("guestSessionId", guest.guestSessionId());
        body.put("state", "ACTIVE");
        body.put("checkedAt", java.time.Instant.now().toString());
        return Response.json(200, write(body));
    }

    // ----- Order: the real read contract (PR #45) -----

    /** The staff credential a test request carries: {@code restofy_access=fake-staff-<restaurant>}. */
    public static final String STAFF_CREDENTIAL_PREFIX = "fake-staff-";

    /** Order takes the restaurant from the staff session, never from the request: here from the forwarded cookie. */
    private static UUID sessionRestaurant(Request request) {
        String cookie = request.header("Cookie");
        if (cookie == null || !cookie.startsWith("restofy_access=")) return null;
        return staffRestaurant(cookie.substring("restofy_access=".length()).trim());
    }

    private static UUID staffRestaurant(String credential) {
        if (credential == null) return null;
        if (credential.startsWith(STAFF_CREDENTIAL_PREFIX)) {
            return UUID.fromString(credential.substring(STAFF_CREDENTIAL_PREFIX.length()));
        }
        // A real Auth access token: its restaurant claim stands for the session Order would look up.
        String[] parts = credential.split("\\.");
        if (parts.length != 3) return null;
        String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]), java.nio.charset.StandardCharsets.UTF_8);
        return UUID.fromString(read(payload).get("restaurant_id").asString());
    }

    private static final Response NO_STAFF_SESSION = Response.json(401, "{\"status\":401,\"code\":\"UNAUTHENTICATED\"}");

    /** Active order count per session: fulfillment RECEIVED, PREPARING, READY or SERVED (not COMPLETED/CANCELLED). */
    private Response sessionSummaries(Request request) {
        UUID restaurantId = staffRestaurant(request.header("X-Restofy-Staff-Delegation"));
        if (restaurantId == null) return NO_STAFF_SESSION;
        Response denied = verifyDelegatedIdentity(request, restaurantId, "order:read");
        if (denied != null) return denied;
        JsonNode body = read(request.body());
        if (body.has("restaurantId")) return rejection(400, "INVALID_REQUEST");
        Set<UUID> sessionIds = new HashSet<>();
        body.get("tableSessionIds").forEach(node -> sessionIds.add(UUID.fromString(node.asString())));
        Map<UUID, Long> counts = new LinkedHashMap<>();
        for (Order order : orders) {
            boolean active = !Set.of("COMPLETED", "CANCELLED").contains(fulfillment(order));
            if (order.restaurantId().equals(restaurantId) && sessionIds.contains(order.sessionId()) && active) {
                counts.merge(order.sessionId(), 1L, Long::sum);
            }
        }
        List<Map<String, Object>> items = counts.entrySet().stream()
                .map(e -> Map.<String, Object>of("tableSessionId", e.getKey(), "activeOrderCount", e.getValue())).toList();
        return Response.json(200, write(Map.of("items", items)));
    }

    private Response searchOrders(Request request) {
        UUID restaurantId = staffRestaurant(request.header("X-Restofy-Staff-Delegation"));
        if (restaurantId == null) return NO_STAFF_SESSION;
        Response denied = verifyDelegatedIdentity(request, restaurantId, "order:read");
        if (denied != null) return denied;
        JsonNode body = read(request.body());
        if (body.has("restaurantId")) return rejection(400, "INVALID_REQUEST");
        UUID tableId = body.hasNonNull("tableId") ? UUID.fromString(body.get("tableId").asString()) : null;
        String view = body.hasNonNull("view") ? body.get("view").asString() : null;
        Set<String> statuses = null;
        if (body.hasNonNull("fulfillmentStatuses")) {
            statuses = new HashSet<>();
            for (JsonNode node : body.get("fulfillmentStatuses")) statuses.add(node.asString());
        }
        if (view != null && statuses != null) return rejection(422, "INVALID_REQUEST");
        Set<UUID> sessions = null;
        if (body.hasNonNull("tableSessionIds")) {
            sessions = new HashSet<>();
            for (JsonNode node : body.get("tableSessionIds")) sessions.add(UUID.fromString(node.asString()));
        }
        int page = body.get("page").asInt();
        int limit = body.get("limit").asInt();
        boolean ascending = body.hasNonNull("sort") && "placedAt,asc".equals(body.get("sort").asString());
        Set<UUID> sessionFilter = sessions;
        Set<String> statusFilter = statuses;
        Comparator<Order> order = ascending ? Comparator.comparing(Order::acceptedAt).thenComparing(Order::id)
                : Comparator.comparing(Order::acceptedAt).reversed().thenComparing(Order::id);
        List<Order> matching = orders.stream()
                .filter(o -> o.restaurantId().equals(restaurantId))
                .filter(o -> tableId == null || o.tableId().equals(tableId))
                .filter(o -> sessionFilter == null || sessionFilter.contains(o.sessionId()))
                .filter(o -> inView(o, view))
                .filter(o -> statusFilter == null || statusFilter.contains(fulfillment(o)))
                .sorted(order).toList();
        int from = (int) Math.min((long) page * limit, matching.size());
        int to = Math.min(from + limit, matching.size());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", matching.subList(from, to).stream().map(FakeBackends::summaryJson).toList());
        response.put("page", page);
        response.put("limit", limit);
        response.put("totalItems", matching.size());
        response.put("totalPages", (matching.size() + limit - 1) / limit);
        response.put("view", view);
        return Response.json(200, write(response));
    }

    private Response getOrder(Request request) {
        UUID restaurantId = sessionRestaurant(request);
        if (restaurantId == null) return NO_STAFF_SESSION;
        Matcher match = ORDER_ID.matcher(request.path());
        match.matches();
        UUID id = UUID.fromString(match.group(1));
        return orders.stream().filter(o -> o.id().equals(id) && o.restaurantId().equals(restaurantId)).findFirst()
                .map(o -> Response.json(200, write(detailJson(o))))
                .orElse(Response.json(404, "{\"status\":404}"));
    }

    private Response getCancellations(Request request) {
        UUID restaurantId = sessionRestaurant(request);
        if (restaurantId == null) return NO_STAFF_SESSION;
        Matcher match = ORDER_CANCELLATIONS.matcher(request.path());
        match.matches();
        UUID id = UUID.fromString(match.group(1));
        return orders.stream().filter(o -> o.id().equals(id) && o.restaurantId().equals(restaurantId)).findFirst()
                .map(o -> {
                    Map<String, Object> json = new LinkedHashMap<>();
                    json.put("orderId", o.id());
                    json.put("cancellations", cancelledQuantity(o) > 0 ? List.of(cancellation(o)) : List.of());
                    return Response.json(200, write(json));
                })
                .orElse(Response.json(404, "{\"status\":404}"));
    }

    /** Order's {@code active} view: fulfillment RECEIVED..SERVED; {@code cancelled}: CANCELLED. */
    private static boolean inView(Order o, String view) {
        if (view == null) return true;
        return switch (view) {
            case "active" -> !Set.of("COMPLETED", "CANCELLED").contains(fulfillment(o));
            case "cancelled" -> fulfillment(o).equals("CANCELLED");
            default -> throw new IllegalArgumentException("unknown view " + view);
        };
    }

    /** Order's single fulfillment axis, derived from the test order's business, delivery and kitchen state. */
    static String fulfillment(Order o) {
        if (o.business().equals("CANCELLED")) return "CANCELLED";
        if (o.business().equals("COMPLETED")) return "COMPLETED";
        if (o.delivery().equals("SERVED")) return "SERVED";
        if ("READY".equals(o.preparation())) return "READY";
        if ("PREPARING".equals(o.preparation())) return "PREPARING";
        return "RECEIVED";
    }

    private static String paymentStatus(Order o) {
        return switch (o.payment()) {
            case "NOT_PAID" -> "NOT_REQUESTED";
            case "UNKNOWN" -> "FAILED";
            default -> o.payment();
        };
    }

    /** One row of a list or search: no items, no version. */
    public static Map<String, Object> summaryJson(Order o) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("orderId", o.id());
        json.put("orderNo", o.number());
        json.put("tableId", o.tableId());
        json.put("tableSessionId", o.sessionId());
        json.put("fulfillmentStatus", fulfillment(o));
        json.put("paymentStatus", paymentStatus(o));
        json.put("currency", "TRY");
        json.put("grossMinor", 15000 - cancelledGrossMinor(o));
        json.put("currentDueMinor", 15000 - cancelledGrossMinor(o));
        json.put("unitCount", 2 - cancelledQuantity(o));
        json.put("placedAt", o.acceptedAt());
        json.put("version", o.version());
        json.put("servedAt", o.servedAt());
        return json;
    }

    public static Map<String, Object> detailJson(Order o) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("orderId", o.id());
        json.put("orderNo", o.number());
        json.put("tableId", o.tableId());
        json.put("tableSessionId", o.sessionId());
        json.put("fulfillmentStatus", fulfillment(o));
        json.put("paymentStatus", paymentStatus(o));
        json.put("currency", "TRY");
        json.put("amounts", Map.of("netMinor", 12500, "vatMinor", 2500, "grossMinor", 15000));
        json.put("currentDueMinor", 15000 - cancelledGrossMinor(o));
        json.put("cancelledGrossMinor", cancelledGrossMinor(o));
        json.put("activeGrossMinor", 15000 - cancelledGrossMinor(o));
        json.put("items", List.of(item(o)));
        json.put("note", null);
        json.put("placedAt", o.acceptedAt());
        json.put("updatedAt", Instant.parse("2026-10-16T10:00:00Z"));
        json.put("version", o.version());
        json.put("servedAt", o.servedAt());
        return json;
    }

    /** Of the two portions on the line: all for a cancelled order, one for a partially cancelled order. */
    private static int cancelledQuantity(Order o) {
        return switch (o.business()) {
            case "CANCELLED" -> 2;
            case "PARTIALLY_CANCELLED" -> 1;
            default -> 0;
        };
    }

    private static long cancelledGrossMinor(Order o) {
        return 7500L * cancelledQuantity(o);
    }

    private static Map<String, Object> item(Order o) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("orderItemId", UUID.nameUUIDFromBytes(("item-" + o.id()).getBytes()));
        item.put("productId", UUID.nameUUIDFromBytes("product".getBytes()));
        item.put("name", "Izgara Köfte");
        item.put("quantity", 2);
        item.put("cancelledQuantity", cancelledQuantity(o));
        item.put("remainingQuantity", 2 - cancelledQuantity(o));
        item.put("removedIngredients", List.of(Map.of("ingredientId", UUID.nameUUIDFromBytes("onion".getBytes()),
                "name", "Soğan")));
        item.put("extras", List.of(Map.of("ingredientId", UUID.nameUUIDFromBytes("cheese".getBytes()),
                "name", "Ekstra peynir", "quantity", 1, "unitPriceMinor", 1000)));
        item.put("note", "Az pişmiş, soğansız");
        item.put("unitGrossMinor", 7500);
        item.put("lineGrossMinor", 15000);
        return item;
    }

    private static Map<String, Object> cancellation(Order o) {
        Map<String, Object> cancellation = new LinkedHashMap<>();
        cancellation.put("cancellationId", UUID.nameUUIDFromBytes(("cancel-" + o.id()).getBytes()));
        cancellation.put("orderId", o.id());
        cancellation.put("scope", "ITEMS");
        cancellation.put("status", "APPLIED");
        cancellation.put("items", List.of(Map.of("orderItemId", UUID.nameUUIDFromBytes(("item-" + o.id()).getBytes()),
                "quantity", cancelledQuantity(o))));
        cancellation.put("reasons", List.of(Map.of("source", "KITCHEN", "code", "OUT_OF_STOCK", "text", "Köfte bitti")));
        cancellation.put("requestedAt", Instant.parse("2026-10-16T09:50:00Z"));
        cancellation.put("decidedAt", Instant.parse("2026-10-16T09:51:00Z"));
        cancellation.put("requestedBy", Map.of("type", "SERVICE", "role", "KITCHEN"));
        return cancellation;
    }

    // ----- helpers -----

    public static String write(Object value) {
        return JSON.writeValueAsString(value);
    }

    public static JsonNode read(String json) {
        return JSON.readTree(json);
    }
}

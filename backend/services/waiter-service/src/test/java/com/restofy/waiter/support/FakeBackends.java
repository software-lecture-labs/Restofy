package com.restofy.waiter.support;

import com.restofy.waiter.support.DownstreamStub.Request;
import com.restofy.waiter.support.DownstreamStub.Response;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-memory QR and Order behind a {@link DownstreamStub}. It behaves like a compliant implementation of the
 * DRAFT contract: it filters by restaurant, applies filters and ordering to the whole data set, then pages.
 * Misbehaving downstreams are simulated per test with their own routes.
 */
public final class FakeBackends {

    public static final JsonMapper JSON = JsonMapper.builder().build();

    public record Table(UUID id, UUID restaurantId, String number, String status, UUID sessionId) {
    }

    /** Test order; {@code preparation} is null, WAITING, PREPARING or READY. */
    public record Order(UUID id, UUID restaurantId, UUID tableId, UUID sessionId, String number, String business,
                        String delivery, String preparation, Instant acceptedAt, Instant readyAt, Instant servedAt) {
    }

    /** A guest credential as QR would know it; the statuses are the DRAFT contract values. */
    public record Guest(String token, UUID restaurantId, UUID tableId, UUID tableSessionId, UUID guestSessionId,
                        String tableSessionStatus, String guestStatus) {

        public Guest withStatuses(String tableSession, String guestSession) {
            return new Guest(token, restaurantId, tableId, tableSessionId, guestSessionId, tableSession, guestSession);
        }
    }

    public final List<Table> tables = new ArrayList<>();
    public final List<Order> orders = new ArrayList<>();
    /** Known guest credentials by token; an unknown token is answered with 404. */
    public final Map<String, Guest> guests = new java.util.concurrent.ConcurrentHashMap<>();

    private static final Pattern TABLE_ID = Pattern.compile("/internal/v1/tables/([0-9a-f-]{36})");
    private static final Pattern ORDER_ID = Pattern.compile("/internal/v1/orders/([0-9a-f-]{36})");

    public FakeBackends installOn(DownstreamStub stub) {
        stub.route("GET", "/internal/v1/tables", this::listTables);
        stub.route("GET", TABLE_ID.pattern(), this::getTable);
        stub.route("POST", "/internal/v1/orders/session-summaries", this::sessionSummaries);
        stub.route("POST", "/internal/v1/orders/search", this::searchOrders);
        stub.route("GET", ORDER_ID.pattern(), this::getOrder);
        stub.route("POST", "/internal/v1/guest-sessions/verify", this::verifyGuest);
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

    public static Map<String, Object> tableJson(Table table) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", table.id());
        json.put("restaurantId", table.restaurantId());
        json.put("tableNumber", table.number());
        json.put("displayName", "Table " + table.number());
        json.put("capacity", 4);
        json.put("operationalStatus", table.status());
        json.put("active", !"DISABLED".equals(table.status()));
        if (table.sessionId() == null) {
            json.put("currentSession", null);
        } else {
            Map<String, Object> session = new LinkedHashMap<>();
            session.put("id", table.sessionId());
            session.put("status", "OPEN");
            session.put("openedAt", "2026-10-16T09:00:00Z");
            session.put("lastActivityAt", "2026-10-16T09:30:00Z");
            session.put("scanCount", 2);
            json.put("currentSession", session);
        }
        return json;
    }

    private Response verifyGuest(Request request) {
        Guest guest = guests.get(read(request.body()).get("guestToken").asString());
        if (guest == null) return Response.json(404, "{\"status\":404}");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("restaurantId", guest.restaurantId());
        body.put("tableId", guest.tableId());
        body.put("tableSession", Map.of("id", guest.tableSessionId(), "status", guest.tableSessionStatus()));
        body.put("guestSession", Map.of("id", guest.guestSessionId(), "status", guest.guestStatus()));
        return Response.json(200, write(body));
    }

    // ----- Order -----

    private Response sessionSummaries(Request request) {
        JsonNode body = read(request.body());
        UUID restaurantId = UUID.fromString(body.get("restaurantId").asString());
        Set<UUID> sessionIds = new HashSet<>();
        body.get("tableSessionIds").forEach(node -> sessionIds.add(UUID.fromString(node.asString())));
        Map<UUID, Long> counts = new LinkedHashMap<>();
        for (Order order : orders) {
            boolean active = order.business().equals("ACCEPTED") || order.business().equals("PARTIALLY_CANCELLED");
            if (order.restaurantId().equals(restaurantId) && sessionIds.contains(order.sessionId()) && active) {
                counts.merge(order.sessionId(), 1L, Long::sum);
            }
        }
        List<Map<String, Object>> items = counts.entrySet().stream()
                .map(e -> Map.<String, Object>of("tableSessionId", e.getKey(), "activeOrderCount", e.getValue())).toList();
        return Response.json(200, write(Map.of("items", items)));
    }

    private Response searchOrders(Request request) {
        JsonNode body = read(request.body());
        UUID restaurantId = UUID.fromString(body.get("restaurantId").asString());
        UUID tableId = body.hasNonNull("tableId") ? UUID.fromString(body.get("tableId").asString()) : null;
        String view = body.hasNonNull("view") ? body.get("view").asString() : null;
        Set<UUID> sessions = null;
        if (body.hasNonNull("tableSessionIds")) {
            sessions = new HashSet<>();
            for (JsonNode node : body.get("tableSessionIds")) sessions.add(UUID.fromString(node.asString()));
        }
        int page = body.get("page").asInt();
        int size = body.get("size").asInt();
        Set<UUID> sessionFilter = sessions;
        Comparator<Order> order = switch (view == null ? "" : view) {
            case "READY_HISTORY" -> Comparator.comparing(Order::readyAt).reversed().thenComparing(Order::id);
            case "AWAITING_DELIVERY" -> Comparator.comparing(Order::readyAt).thenComparing(Order::id);
            default -> Comparator.comparing(Order::acceptedAt).reversed().thenComparing(Order::id);
        };
        List<Order> matching = orders.stream()
                .filter(o -> o.restaurantId().equals(restaurantId))
                .filter(o -> tableId == null || o.tableId().equals(tableId))
                .filter(o -> sessionFilter == null || sessionFilter.contains(o.sessionId()))
                .filter(o -> inView(o, view))
                .sorted(order).toList();
        int from = (int) Math.min((long) page * size, matching.size());
        int to = Math.min(from + size, matching.size());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", matching.subList(from, to).stream().map(FakeBackends::orderJson).toList());
        response.put("page", page);
        response.put("size", size);
        response.put("totalElements", matching.size());
        return Response.json(200, write(response));
    }

    private Response getOrder(Request request) {
        Matcher match = ORDER_ID.matcher(request.path());
        match.matches();
        UUID id = UUID.fromString(match.group(1));
        UUID restaurantId = UUID.fromString(request.query().get("restaurantId"));
        return orders.stream().filter(o -> o.id().equals(id) && o.restaurantId().equals(restaurantId)).findFirst()
                .map(o -> Response.json(200, write(orderJson(o))))
                .orElse(Response.json(404, "{\"status\":404}"));
    }

    private static boolean inView(Order o, String view) {
        if (view == null) return true;
        boolean ready = "READY".equals(o.preparation()) && !o.business().equals("CANCELLED");
        return switch (view) {
            case "ACTIVE" -> o.business().equals("ACCEPTED") || o.business().equals("PARTIALLY_CANCELLED");
            case "READY_HISTORY" -> ready;
            case "AWAITING_DELIVERY" -> ready && o.delivery().equals("PENDING");
            case "CANCELLED" -> o.business().equals("CANCELLED");
            default -> throw new IllegalArgumentException("unknown view " + view);
        };
    }

    public static Map<String, Object> orderJson(Order o) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", o.id());
        json.put("restaurantId", o.restaurantId());
        json.put("orderNumber", o.number());
        json.put("tableId", o.tableId());
        json.put("tableSessionId", o.sessionId());
        json.put("businessStatus", o.business());
        json.put("deliveryStatus", o.delivery());
        json.put("acceptedAt", o.acceptedAt());
        json.put("servedAt", o.servedAt());
        if (o.preparation() == null) {
            json.put("preparation", null);
        } else {
            Map<String, Object> prep = new LinkedHashMap<>();
            prep.put("status", o.preparation());
            prep.put("startedAt", o.readyAt() == null ? null : o.readyAt().minusSeconds(300));
            prep.put("readyAt", o.readyAt());
            prep.put("sourceVersion", 3);
            prep.put("asOf", Instant.parse("2026-10-16T10:00:00Z"));
            json.put("preparation", prep);
        }
        json.put("payment", Map.of("status", "NOT_PAID", "sourceVersion", 1, "asOf", Instant.parse("2026-10-16T10:00:00Z")));
        json.put("currency", "TRY");
        json.put("originalGrossTotal", new BigDecimal("150.00"));
        json.put("cancelledGrossTotal", o.business().equals("CANCELLED") ? new BigDecimal("150.00") : BigDecimal.ZERO);
        json.put("payableTotal", o.business().equals("CANCELLED") ? BigDecimal.ZERO : new BigDecimal("150.00"));
        json.put("items", List.of(item(o)));
        json.put("cancellations", o.business().equals("CANCELLED") ? List.of(cancellation(o)) : List.of());
        json.put("version", 4);
        return json;
    }

    private static Map<String, Object> item(Order o) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", UUID.nameUUIDFromBytes(("item-" + o.id()).getBytes()));
        item.put("productId", UUID.nameUUIDFromBytes("product".getBytes()));
        item.put("productName", "Izgara Köfte");
        item.put("quantity", 2);
        item.put("cancelledQuantity", o.business().equals("CANCELLED") ? 2 : 0);
        item.put("unitGrossPrice", new BigDecimal("75.00"));
        item.put("lineGrossTotal", new BigDecimal("150.00"));
        item.put("note", "Az pişmiş, soğansız");
        item.put("options", List.of(
                Map.of("name", "Ekstra peynir", "action", "ADD", "quantity", 1, "unitPriceDelta", new BigDecimal("10.00")),
                Map.of("name", "Soğan", "action", "REMOVE", "quantity", 1, "unitPriceDelta", BigDecimal.ZERO)));
        return item;
    }

    private static Map<String, Object> cancellation(Order o) {
        Map<String, Object> cancellation = new LinkedHashMap<>();
        cancellation.put("id", UUID.nameUUIDFromBytes(("cancel-" + o.id()).getBytes()));
        cancellation.put("source", "KITCHEN");
        cancellation.put("status", "APPROVED");
        cancellation.put("reasonCode", "OUT_OF_STOCK");
        cancellation.put("reasonText", "Köfte bitti");
        cancellation.put("requestedAt", Instant.parse("2026-10-16T09:50:00Z"));
        cancellation.put("decidedAt", Instant.parse("2026-10-16T09:51:00Z"));
        cancellation.put("items", List.of(Map.of("orderItemId", UUID.nameUUIDFromBytes(("item-" + o.id()).getBytes()), "quantity", 2)));
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
